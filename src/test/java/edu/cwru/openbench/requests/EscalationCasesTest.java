package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.Verifies;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The test cases of issue #7 (docs/test-cases: Matched Allocation): a refused approval asks for a
 * replacement without restarting the Objection Window; with no Candidate left a Required line
 * refuses its Request and an Optional one is Dropped; and when the window lapses with an approval
 * outstanding, the Capacity Planner decides it, and every later approval of the Request. Step by
 * step through the API; the lapse runs as {@code DueWorkJob} would call it, with the movable Clock.
 */
class EscalationCasesTest extends EscalationWorldTest {

    @Test
    @DisplayName("mat-refuse-replace: a refusal asks for a replacement without restarting the Objection Window")
    @Verifies("mat-refuse-replace")
    void aRefusalAsksForAReplacementWithoutRestartingTheWindow() throws Exception {
        LocalDate d = dayD();
        A1 a1 = pendingA1(d);
        Instant w = windowEndOf(a1.id());
        assertThat(account(platform)).containsEntry("held", new BigDecimal("30.00"));

        // 1. Oscar refuses with a reason: recorded with actor, reason, and timestamp.
        Instant now = clock.instant();
        decide(as(oscar), approvalOf(a1.m1()), "REFUSE", "Dave is on another project")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REFUSED"))
                .andExpect(jsonPath("$.decidedByName").value("Oscar Reed"))
                .andExpect(jsonPath("$.reason").value("Dave is on another project"));
        Map<String, Object> row = jdbc.queryForMap("SELECT decided_by, decided_at FROM approval WHERE id = ?",
                approvalOf(a1.m1()));
        assertThat(row.get("decided_by")).isEqualTo(world.oscar().id());
        assertThat(((Timestamp) row.get("decided_at")).toInstant()).isBetween(now.minusSeconds(60), now.plusSeconds(60));

        // 2. A1 is Waiting; L1's Hold is released and 30.00 no longer held; a replacement is asked; still W.
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("REFUSED"))
                .andExpect(jsonPath("$.lines[0].reason").value("REPLACEMENT_NEEDED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("EVALUATED"));
        assertThat(liveRowsOf(a1.id())).isZero();
        assertThat(account(platform)).containsEntry("held", new BigDecimal("0.00"));
        assertThat(noticesAbout(world.alice().id(), "REQUEST_UPDATE", a1.id()))
                .anySatisfy(text -> assertThat(text).contains("Oscar Reed refused Dave Kim")
                        .contains("Select a replacement"));
        assertThat(windowEndOf(a1.id())).isEqualTo(w);

        // 3. Rita Gomez from the Shortlist: Pending again, L1 on Hold again, an approval to Bob Patel; still W.
        shortlist(as(alice), a1.id(), a1.m1()).andExpect(jsonPath("$.candidates[*].name", hasItem("Rita Gomez")));
        select(as(alice), a1.id(), a1.m1(), world.rita().id()).andExpect(status().isOk());
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("PENDING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[1].line.state").value("HELD"));
        assertThat(account(platform)).containsEntry("held", new BigDecimal("30.00"));
        approvals(as(bob)).andExpect(jsonPath("$[*].candidateName", contains("Rita Gomez")));
        assertThat(notificationsOf(world.bob())).containsExactly("APPROVAL_REQUESTED");
        assertThat(windowEndOf(a1.id())).isEqualTo(w);

        // 4. Bob approves: A1 is Allocated; Rita has a Loan to Platform for D to D+1.
        decide(as(bob), approvalOf(a1.m1(), world.rita().id()), "APPROVE", "").andExpect(status().isOk());
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[0].loans[*].memberName", contains("Rita Gomez")))
                .andExpect(jsonPath("$.lines[0].loans[0].borrowingTeam.id").value((int) platform))
                .andExpect(jsonPath("$.lines[0].loans[0].days.from").value(d.toString()))
                .andExpect(jsonPath("$.lines[0].loans[0].days.to").value(d.plusDays(1).toString()))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));

        // The Objection Window never restarted, and Dave Kim has no Loan.
        assertThat(windowEndOf(a1.id())).isEqualTo(w);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE member_id = ?", Integer.class,
                world.dave().id())).isZero();
    }

    @Test
    @DisplayName("mat-refuse-none: a refusal with no Candidate left refuses a Required line and drops an Optional one")
    @Verifies("mat-refuse-none")
    void aRefusalWithNoCandidateLeftRefusesOrDrops() throws Exception {
        LocalDate d = dayD();

        // 1. B1 (M4 names Dave Kim, Required; L1 Room A): Oscar refuses Dave, and B1 is Refused.
        long b1 = createDraft(as(bob));
        long m4 = id(addNamedPersonLine(as(bob), b1, 1, d, d.plusDays(1), world.dave().id()));
        line(as(bob), b1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("PENDING"));
        assertThat(liveRowsOf(b1)).isEqualTo(1);
        decide(as(oscar), approvalOf(m4), "REFUSE", "Dave is needed here").andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("REFUSED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("CANCELED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("CANCELED"))
                .andExpect(jsonPath("$.history[-1].toState").value("REFUSED"));
        assertThat(liveRowsOf(b1)).isZero();
        assertThat(account(research)).containsEntry("held", new BigDecimal("0.00"));
        assertThat(noticesAbout(world.bob().id(), "REQUEST_UPDATE", b1))
                .anySatisfy(text -> assertThat(text).contains("is Refused").contains("named that Person"));

        // 2. B2 (L2 Room B, Required; M5 Statistics, Optional): Allocated at once; L2 charged; M5 open.
        long b2 = createDraft(as(bob));
        long l2 = line(as(bob), b2, room, 1, at(d, 10), at(d, 12), roomB);
        long m5 = personLine(as(bob), b2, 1, d, d.plusDays(1), statistics);
        markOptional(as(bob), b2, m5);
        submit(as(bob), b2).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(bob), b2)
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("AWAITING_APPROVAL"));

        // 3. Dave Kim for M5; Oscar refuses. No other Candidate: M5 is Dropped with its reason, B2 stays Allocated.
        shortlist(as(bob), b2, m5).andExpect(jsonPath("$.candidates[*].name", contains("Dave Kim")));
        select(as(bob), b2, m5, world.dave().id()).andExpect(status().isOk());
        decide(as(oscar), approvalOf(m5), "REFUSE", "Dave is busy").andExpect(status().isOk());
        detail(as(bob), b2)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("DROPPED"))
                .andExpect(jsonPath("$.lines[1].reason").value("APPROVAL_REFUSED"))
                .andExpect(jsonPath("$.lines[1].reasonMessage").value(org.hamcrest.Matchers.containsString(
                        "No other Candidate is left")));

        // 4. Research's Statement: one Charge of 30.00, for L2; nothing for B1.
        statement(bob, research)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].amount", contains(30.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].line", contains((int) l2)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM transaction t JOIN request_line l ON l.id = t.line_id WHERE l.request_id = ?""",
                Integer.class, b1)).isZero();
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    @Test
    @DisplayName("mat-escalate: a silent Manager's approval escalates to the Capacity Planner")
    @Verifies("mat-escalate")
    void aSilentManagersApprovalEscalatesToTheCapacityPlanner() throws Exception {
        LocalDate d = dayD();
        A1 a1 = pendingA1(d);
        Instant w = windowEndOf(a1.id());
        long approval = approvalOf(a1.m1());

        // 1. [clock] The window lapses: A1 is Waiting, L1's Hold released, escalated; Alice and Erin are told.
        lapse(a1.id());
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].candidates[0].escalated").value(true));
        assertThat(liveRowsOf(a1.id())).isZero();
        assertThat(noticesAbout(world.alice().id(), "ESCALATED", a1.id())).singleElement()
                .satisfies(text -> assertThat(text).contains("is escalated"));
        assertThat(noticesAbout(erinId, "ESCALATED", a1.id())).hasSize(1);

        // 2. Escalations: A1 with its outstanding approval (Dave Kim), the silent Manager, and the lapse.
        String body = escalations(erin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> listed = JsonPath.read(body, "$[?(@.id == " + a1.id() + ")]");
        assertThat(listed).singleElement().satisfies(entry -> {
            assertThat(Instant.parse((String) entry.get("lapsedAt"))).isEqualTo(w);
            assertThat(entry.get("state")).isEqualTo("WAITING");
            List<Map<String, Object>> approvals = (List<Map<String, Object>>) entry.get("approvals");
            assertThat(approvals).singleElement().satisfies(each -> {
                assertThat(each.get("candidateName")).isEqualTo("Dave Kim");
                assertThat(each.get("silentManagerName")).isEqualTo("Oscar Reed");
                assertThat(((Number) each.get("id")).longValue()).isEqualTo(approval);
            });
        });

        // 3. Erin approves on Oscar's behalf: recorded with actor Erin Walsh; A1 is Allocated at its locked Quotes.
        decide(as(erin), approval, "APPROVE", "Oscar is away")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByName").value("Erin Walsh"));
        assertThat(jdbc.queryForMap("SELECT approver_member_id, decided_by, escalated FROM approval WHERE id = ?", approval))
                .containsEntry("approver_member_id", world.oscar().id())
                .containsEntry("decided_by", erinId)
                .containsEntry("escalated", true);
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.quote").value(30.00));
        assertThat(transactions(platform, "CHARGE")).containsExactly(new BigDecimal("30.00"));
        assertThat(noticesAbout(world.oscar().id(), "ESCALATED", a1.id())).singleElement()
                .satisfies(text -> assertThat(text).contains("Erin Walsh").contains("approved").contains("allocated"));

        // 4. Oscar's Approvals: no longer open; shown as decided by the Capacity Planner.
        approvals(as(oscar))
                .andExpect(jsonPath("$[?(@.state == 'OUTSTANDING')]", empty()))
                .andExpect(jsonPath("$[?(@.id == " + approval + ")].decidedByName", contains("Erin Walsh")))
                .andExpect(jsonPath("$[?(@.id == " + approval + ")].escalated", contains(true)));
        escalations(erin).andExpect(jsonPath("$[?(@.id == " + a1.id() + ")]", empty()));

        // Exactly one decision was recorded, by Erin.
        decide(as(oscar), approval, "REFUSE", "Too late")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM request_event WHERE request_id = ? AND line_id = ? AND note LIKE '%approved by%'""",
                Integer.class, a1.id(), a1.m1())).isEqualTo(1);
    }

    @Test
    @DisplayName("mat-escalate-later: after the lapse, a later approval goes straight to the Capacity Planner")
    @Verifies("mat-escalate-later")
    void aLaterApprovalGoesStraightToTheCapacityPlanner() throws Exception {
        LocalDate d = dayD();
        A1 a1 = pendingA1(d);
        lapse(a1.id());
        decide(as(erin), approvalOf(a1.m1()), "REFUSE", "Dave is on another project").andExpect(status().isOk());
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("REFUSED"));

        // 1. Rita Gomez: the approval request goes to Erin Walsh, not to Bob Patel; no Hold is placed.
        select(as(alice), a1.id(), a1.m1(), world.rita().id()).andExpect(status().isOk());
        long rita = approvalOf(a1.m1(), world.rita().id());
        assertThat(jdbc.queryForObject("SELECT escalated FROM approval WHERE id = ?", Boolean.class, rita)).isTrue();
        assertThat(noticesAbout(erinId, "APPROVAL_REQUESTED", a1.id())).singleElement()
                .satisfies(text -> assertThat(text).contains("Rita Gomez"));
        approvals(as(bob)).andExpect(jsonPath("$", empty()));
        approvals(as(erin)).andExpect(jsonPath("$[?(@.id == " + rita + ")].candidateName", contains("Rita Gomez")));
        detail(as(alice), a1.id()).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(liveRowsOf(a1.id())).isZero();
        assertThat(account(platform)).containsEntry("held", new BigDecimal("0.00"));

        // 2. Erin approves it from Escalations: Room A is still free, so A1 is Allocated; Rita has a Loan.
        escalations(erin).andExpect(jsonPath("$[?(@.id == " + a1.id() + ")].approvals[*].candidateName",
                contains("Rita Gomez")));
        decide(as(erin), rita, "APPROVE", "").andExpect(status().isOk());
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].loans[*].memberName", contains("Rita Gomez")))
                .andExpect(jsonPath("$.lines[0].loans[0].borrowingTeam.id").value((int) platform))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));
        assertThat(transactions(platform, "CHARGE")).containsExactly(new BigDecimal("30.00"));

        // Bob never received an approval request for A1.
        assertThat(notificationsOf(world.bob())).doesNotContain("APPROVAL_REQUESTED");
        assertThat(noticesAbout(world.bob().id(), "ESCALATED", a1.id())).singleElement()
                .satisfies(text -> assertThat(text).contains("Erin Walsh").contains("Rita Gomez"));
    }

    @Test
    @DisplayName("An Optional line refused with Candidates left asks for a replacement; the Pending Request keeps its Holds")
    void anOptionalRefusalKeepsTheHolds() throws Exception {
        LocalDate d = dayD();
        // A2: a Required line naming Dave Kim, an Optional Statistics line, and Room B.
        long a2 = createDraft(as(alice));
        long required = id(addNamedPersonLine(as(alice), a2, 1, d.plusDays(7), d.plusDays(8), world.dave().id()));
        long optional = personLine(as(alice), a2, 1, d.plusDays(9), d.plusDays(10), statistics);
        markOptional(as(alice), a2, optional);
        line(as(alice), a2, room, 1, at(d.plusDays(7), 10), at(d.plusDays(7), 12), roomB);
        submit(as(alice), a2).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a2, optional, world.dave().id()).andExpect(status().isOk());

        decide(as(oscar), approvalOf(optional), "REFUSE", "Not that week").andExpect(status().isOk());
        detail(as(alice), a2)
                .andExpect(jsonPath("$.request.state").value("PENDING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[1].line.state").value("REFUSED"))
                .andExpect(jsonPath("$.lines[1].reason").value("REPLACEMENT_NEEDED"))
                .andExpect(jsonPath("$.lines[2].line.state").value("HELD"));
        assertThat(liveRowsOf(a2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM approval WHERE id = ?", String.class, approvalOf(required)))
                .isEqualTo("OUTSTANDING");

        // Rita replaces Dave on the Optional line; A2 stays Pending.
        select(as(alice), a2, optional, world.rita().id()).andExpect(status().isOk());
        detail(as(alice), a2)
                .andExpect(jsonPath("$.request.state").value("PENDING"))
                .andExpect(jsonPath("$.lines[1].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.history[*].toState", not(hasItem("WAITING"))));
        approvals(as(bob)).andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------------------------------------------------------------- the Request A1 of the cases

    /** A1's id and its Person line M1. */
    record A1(long id, long m1) {}

    /**
     * Alice's Pending A1: M1 (Statistics, 1, D to D+1, Required) with Dave Kim selected, and L1 (Room A,
     * D 10:00-12:00, 30.00) on Hold.
     */
    A1 pendingA1(LocalDate d) throws Exception {
        long draft = createDraft(as(alice));
        long m1 = personLine(as(alice), draft, 1, d, d.plusDays(1), statistics);
        line(as(alice), draft, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), draft).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), draft, m1, world.dave().id()).andExpect(status().isOk());
        detail(as(alice), draft).andExpect(jsonPath("$.lines[1].line.state").value("HELD"));
        return new A1(draft, m1);
    }
}
