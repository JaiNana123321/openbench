package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The rules of allocation with Person lines (design {@code allocate}, steps a to g; Scenario S2 and
 * its failure branches) and their refusals: a Candidate who gained a Loan elsewhere, all or nothing,
 * the Balance at allocation, the waitlist fill, and who may see a Member's Loans.
 */
class LoanRulesTest extends MatchedWorldTest {

    @Test
    @DisplayName("A Required line whose Approved Candidate was lent elsewhere first waits for a replacement; the Holds are released")
    void aRequiredLineWaitsWhenItsCandidateWasLentElsewhere() throws Exception {
        LocalDate d = dayD();
        // Bob's B: M1 (Statistics, Dave), M2 (Ben Ortiz), L (Microscope 1, D 09:00-17:00, 480.00 held).
        long b = createDraft(as(bob));
        long m1 = personLine(as(bob), b, 1, d, d.plusDays(1), statistics);
        long m2 = id(addNamedPersonLine(as(bob), b, 1, d, d.plusDays(1), world.ben().id()));
        long l = line(as(bob), b, microscope, 1, at(d, 9), at(d, 17), null);
        submit(as(bob), b).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(bob), b, m1, world.dave().id()).andExpect(status().isOk());
        long daveForB = approvalOf(m1);
        decide(as(oscar), daveForB, "APPROVE", "").andExpect(status().isOk());

        // Alice's A takes Dave first: Allocated, with Dave's Loan to Platform.
        long a = createDraft(as(alice));
        long am1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a).andExpect(status().isOk());
        select(as(alice), a, am1, world.dave().id()).andExpect(status().isOk());
        decide(as(oscar), approvalOf(am1), "APPROVE", "").andExpect(status().isOk());
        detail(as(alice), a).andExpect(jsonPath("$.request.state").value("ALLOCATED"));

        // The last approval of B: M1 is unsatisfied, so B waits with a replacement asked, before any commit.
        decide(as(alice), approvalOf(m2), "APPROVE", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));
        detail(as(bob), b)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("NOT_A_CANDIDATE"))
                .andExpect(jsonPath("$.lines[0].candidates[0].state").value("REFUSED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("APPROVED"))
                .andExpect(jsonPath("$.lines[1].candidates[0].state").value("APPROVED"))
                .andExpect(jsonPath("$.lines[2].line.state").value("EVALUATED"));
        assertThat(jdbc.queryForMap("SELECT state, decided_by, reason FROM approval WHERE id = ?", daveForB))
                .containsEntry("state", "REFUSED")
                .containsEntry("decided_by", null)
                .hasEntrySatisfying("reason", reason -> assertThat((String) reason).contains("Dave Kim"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE line_id IN (?, ?)", Integer.class, m1, m2))
                .isZero();
        assertThat(account(research)).containsEntry("held", new BigDecimal("0.00"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exclusive_occurrence WHERE line_id = ? AND state IN ('HELD', 'ALLOCATED')""",
                Integer.class, l)).isZero();
        assertThat(charges(research)).isEmpty();
        assertThat(jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = 'REQUEST_UPDATE'",
                String.class, world.bob().id())).anySatisfy(text -> assertThat(text).contains("is Waiting")
                        .contains("Select a replacement"));
    }

    @Test
    @DisplayName("An Optional line whose Approved Candidate was lent elsewhere first is Dropped, and the Request is Allocated")
    void anOptionalLineIsDroppedWhenItsCandidateWasLentElsewhere() throws Exception {
        LocalDate d = dayD();
        // Bob's B: M2 (Ben Ortiz, Required) and M1 (Statistics, Dave, Optional).
        long b = createDraft(as(bob));
        long m2 = id(addNamedPersonLine(as(bob), b, 1, d, d.plusDays(1), world.ben().id()));
        long m1 = personLine(as(bob), b, 1, d, d.plusDays(1), statistics);
        markOptional(as(bob), b, m1);
        submit(as(bob), b).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(bob), b, m1, world.dave().id()).andExpect(status().isOk());
        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b).andExpect(jsonPath("$.request.state").value("PENDING"));

        long a = createDraft(as(alice));
        long am1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a).andExpect(status().isOk());
        select(as(alice), a, am1, world.dave().id()).andExpect(status().isOk());
        decide(as(oscar), approvalOf(am1), "APPROVE", "").andExpect(status().isOk());

        decide(as(alice), approvalOf(m2), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("DROPPED"))
                .andExpect(jsonPath("$.lines[1].reason").value("NOT_A_CANDIDATE"))
                .andExpect(jsonPath("$.lines[1].loans", empty()));
        assertThat(earnings(platform)).containsExactly(new BigDecimal("800.00"));
        assertThat(earnings(operations)).as("one Earning, for Dave's Loan to Platform").hasSize(1);
    }

    @Test
    @DisplayName("All or nothing: a Loan that fails at commit (ALLOCATION_CONFLICT) leaves the Holds Held and posts no Charge")
    void aFailedLoanLeavesNothingOfTheAllocation() throws Exception {
        LocalDate d = dayD();
        // Dave on two Required lines of one Request with the same days: each line's re-evaluation sees him
        // free, so only the commit guard finds the second Loan, after the Holds committed and the Charge posted.
        // Selection refuses Dave for M2 (issue #22), so his Candidate row is written directly: a path that
        // skipped the selection rule, which the guard exists for.
        long b = createDraft(as(bob));
        long m1 = id(addNamedPersonLine(as(bob), b, 1, d, d.plusDays(1), world.dave().id()));
        long m2 = personLine(as(bob), b, 1, d, d.plusDays(1), statistics);
        long l = line(as(bob), b, microscope, 1, at(d, 9), at(d, 17), null);
        submit(as(bob), b).andExpect(jsonPath("$.state").value("PENDING"));
        refused(select(as(bob), b, m2, world.dave().id()), "NOT_A_CANDIDATE");
        long candidate = jdbc.queryForObject("""
                INSERT INTO candidate (line_id, request_id, team_id, member_id, position, starts_on, ends_on)
                VALUES (?, ?, ?, ?, 1, ?, ?) RETURNING id""", Long.class, m2, b, research, world.dave().id(), d,
                d.plusDays(1));
        jdbc.update("INSERT INTO approval (candidate_id, approver_member_id) VALUES (?, ?)", candidate,
                world.oscar().id());
        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        Map<String, Object> before = account(research);

        decide(as(oscar), approvalOf(m2), "APPROVE", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_CONFLICT"));

        detail(as(bob), b)
                .andExpect(jsonPath("$.request.state").value("PENDING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("APPROVED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[1].candidates[0].state").value("OUTSTANDING"))
                .andExpect(jsonPath("$.lines[2].line.state").value("HELD"));
        assertThat(jdbc.queryForList("SELECT state FROM exclusive_occurrence WHERE line_id = ?", String.class, l))
                .containsExactly("HELD");
        assertThat(account(research)).isEqualTo(before).containsEntry("held", new BigDecimal("480.00"));
        assertThat(charges(research)).isEmpty();
        assertThat(earnings(operations)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE member_id = ?", Integer.class,
                world.dave().id())).isZero();
        assertThat(notificationsOf(world.dave())).doesNotContain("LOAN_FORMED");
    }

    @Test
    @DisplayName("If the Balance no longer covers the Required Charges at allocation, the Request waits with INSUFFICIENT_BALANCE; the approval is kept")
    void aShortBalanceAtAllocationMakesTheRequestWait() throws Exception {
        LocalDate d = dayD();
        long b = createDraft(as(bob));
        long m1 = personLine(as(bob), b, 1, d, d.plusDays(1), statistics);
        long l = line(as(bob), b, microscope, 1, at(d, 9), at(d, 17), null);
        submit(as(bob), b).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(bob), b, m1, world.dave().id()).andExpect(status().isOk());
        // Research's granted credits expire while B is Pending, as a Period rollover would expire them.
        jdbc.update("UPDATE account SET granted = 0 WHERE team_id = ?", research);

        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());

        detail(as(bob), b)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("APPROVED"))
                .andExpect(jsonPath("$.lines[0].candidates[0].state").value("APPROVED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("EVALUATED"))
                .andExpect(jsonPath("$.history[-1].toState").value("WAITING"))
                .andExpect(jsonPath("$.history[-1].note").value(org.hamcrest.Matchers.startsWith("INSUFFICIENT_BALANCE")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE line_id = ?", Integer.class, l)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE line_id = ?", Integer.class, m1)).isZero();
        assertThat(earnings(operations)).isEmpty();
    }

    @Test
    @DisplayName("Capacity freed fills a Pending Request whose approvals are in: its Loans form with the system as actor")
    void theWaitlistFillFormsTheLoans() throws Exception {
        LocalDate d = dayD();
        // Alice holds Room A on D 10:00-12:00.
        long x = createDraft(as(alice));
        long xl = line(as(alice), x, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), x).andExpect(jsonPath("$.state").value("ALLOCATED"));

        // Bob's B: Room A at the same hours (waits) and Dave named: Pending.
        long b = createDraft(as(bob));
        long bl = line(as(bob), b, room, 1, at(d, 10), at(d, 12), roomA);
        long m1 = id(addNamedPersonLine(as(bob), b, 1, d, d.plusDays(1), world.dave().id()));
        submit(as(bob), b).andExpect(jsonPath("$.state").value("PENDING"));
        detail(as(bob), b).andExpect(jsonPath("$.lines[0].line.state").value("WAITING"));

        // Every Person line approved, but Room A is still taken: B stays Pending.
        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b)
                .andExpect(jsonPath("$.request.state").value("PENDING"))
                .andExpect(jsonPath("$.lines[1].line.state").value("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE line_id = ?", Integer.class, m1)).isZero();

        // Alice releases Room A: B is filled from the waitlist, and Dave's Loan forms.
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", x, xl).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());
        detail(as(bob), b)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").doesNotExist())
                .andExpect(jsonPath("$.lines[1].line.state").value("LOANED"));
        assertThat(jdbc.queryForList("SELECT actor_id FROM transaction WHERE line_id IN (?, ?) ORDER BY id",
                Long.class, bl, m1)).as("the Charge and the Earning, by the system").containsExactly(null, null);
        assertThat(earnings(operations)).containsExactly(new BigDecimal("800.00"));
        assertThat(jdbc.queryForList("SELECT kind FROM notification WHERE member_id = ? ORDER BY id", String.class,
                world.bob().id())).contains("WAITLIST_FILLED");
        assertThat(notificationsOf(world.dave())).contains("LOAN_FORMED");
    }

    @Test
    @DisplayName("GET /members/{id}/loans: the Member, their Manager, a Planner, and the borrowing Manager; anyone else NOT_FOUND")
    void loansAreScopedAsTheProfileIs() throws Exception {
        LocalDate d = dayD();
        long loan = testData().loan(world.ben().id(), platform, research, d, d.plusDays(1));

        loansOf(ben, "me").andExpect(status().isOk()).andExpect(jsonPath("$[*].id", contains((int) loan)));
        loansOf(alice, world.ben().id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberName").value("Ben Ortiz"))
                .andExpect(jsonPath("$[0].lendingTeam.id").value((int) platform))
                .andExpect(jsonPath("$[0].borrowingTeam.id").value((int) research))
                .andExpect(jsonPath("$[0].daysLent").value(2))
                .andExpect(jsonPath("$[0].endedEarly").value(false));
        loansOf(erin, world.ben().id()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        loansOf(bob, world.ben().id()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

        loansOf(oscar, world.ben().id()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        loansOf(dave, world.ben().id()).andExpect(status().isNotFound());
        loansOf(alice, Long.MAX_VALUE).andExpect(status().isNotFound());
        // A Member's own Manager sees an empty list for a Member who was never lent.
        loansOf(oscar, world.dave().id()).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
        mvc.perform(get("/api/members/me/loans")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Issue #22: a Person selected on another line of the Request for overlapping days is no Candidate")
    void aPersonSelectedOnAnOverlappingLineIsNoCandidate() throws Exception {
        LocalDate d = dayD();
        // Alice's A: M1 and M2 ask for Statistics on the same days, M3 on later days.
        long a = createDraft(as(alice));
        long m1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        long m2 = personLine(as(alice), a, 1, d.plusDays(1), d.plusDays(2), statistics);
        long m3 = personLine(as(alice), a, 1, d.plusDays(7), d.plusDays(8), statistics);
        submit(as(alice), a).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a, m1, world.dave().id()).andExpect(status().isOk());

        // Dave is off M2's Shortlist and refused on M2; M3's days do not overlap, so he is a Candidate there.
        shortlist(as(alice), a, m2).andExpect(jsonPath("$.candidates[*].name", contains("Rita Gomez")));
        refused(select(as(alice), a, m2, world.dave().id()), "NOT_A_CANDIDATE");
        shortlist(as(alice), a, m3).andExpect(jsonPath("$.candidates[*].name",
                org.hamcrest.Matchers.containsInAnyOrder("Dave Kim", "Rita Gomez")));
        select(as(alice), a, m3, world.dave().id()).andExpect(status().isOk());

        // Still refused while his approval on M1 is open; once Oscar refuses it, he is a Candidate for M2 again.
        testData().holds(carolId, statistics);
        testData().available(carolId, LocalDate.now(clock), d.plusDays(60));
        select(as(alice), a, m2, world.rita().id()).andExpect(status().isOk());
        decide(as(bob), approvalOf(m2), "REFUSE", "").andExpect(status().isOk());
        refused(select(as(alice), a, m2, world.dave().id()), "NOT_A_CANDIDATE");
        decide(as(oscar), jdbc.queryForObject("""
                SELECT a.id FROM approval a JOIN candidate c ON c.id = a.candidate_id
                WHERE c.line_id = ? AND c.member_id = ?""", Long.class, m1, world.dave().id()), "REFUSE", "")
                .andExpect(status().isOk());
        shortlist(as(alice), a, m2).andExpect(jsonPath("$.candidates[*].name", org.hamcrest.Matchers.hasItem("Dave Kim")));
    }

    // ---------------------------------------------------------------- helpers

    private void refused(ResultActions result, String code) throws Exception {
        result.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(code));
    }

    private ResultActions loansOf(String caller, Object member) throws Exception {
        return mvc.perform(get("/api/members/{id}/loans", member).with(as(caller)));
    }

    private List<BigDecimal> earnings(long team) {
        return jdbc.queryForList("SELECT amount FROM transaction WHERE team_id = ? AND kind = 'EARNING' ORDER BY id",
                BigDecimal.class, team);
    }

    private List<BigDecimal> charges(long team) {
        return jdbc.queryForList("SELECT amount FROM transaction WHERE team_id = ? AND kind = 'CHARGE' ORDER BY id",
                BigDecimal.class, team);
    }
}
