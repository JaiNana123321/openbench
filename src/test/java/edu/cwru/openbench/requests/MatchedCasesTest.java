package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.Verifies;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Matched Allocation test cases (docs/test-cases, Section "Matched Allocation") that issue #5
 * covers, step by step through the API, on the Matched data of the test world.
 */
class MatchedCasesTest extends MatchedWorldTest {

    @Test
    @DisplayName("mat-shortlist-build: the Shortlist holds the qualified Members of other Teams, and no one is selected")
    @Verifies("mat-shortlist-build")
    void theShortlistHoldsTheQualifiedMembersOfOtherTeams() throws Exception {
        LocalDate d = dayD();

        // 1. A Draft with M1 (Statistics, 1, D to D+1, Required), submitted: Pending, M1 needs an approval.
        long draft = createDraft(as(alice));
        long m1 = personLine(as(alice), draft, 1, d, d.plusDays(1), statistics);
        submit(as(alice), draft).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        detail(as(alice), draft)
                .andExpect(jsonPath("$.lines[0].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[0].candidates", empty()));

        // 2. Dave Kim (Operations, Statistics, 0), then Rita Gomez (Research, Microscopy and Statistics, 0); no Ben.
        shortlist(as(alice), draft, m1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].name", contains("Dave Kim", "Rita Gomez")))
                .andExpect(jsonPath("$.candidates[*].position", contains(1, 2)))
                .andExpect(jsonPath("$.candidates[0].team.id").value((int) operations))
                .andExpect(jsonPath("$.candidates[0].skills", contains(statisticsName)))
                .andExpect(jsonPath("$.candidates[0].personDaysLent").value(0))
                .andExpect(jsonPath("$.candidates[1].team.id").value((int) research))
                .andExpect(jsonPath("$.candidates[1].skills", hasSize(2)))
                .andExpect(jsonPath("$.candidates[1].personDaysLent").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate WHERE line_id = ?", Integer.class, m1))
                .as("Candidates selected by the system").isZero();

        // 3. Each row shows name, Team, skills, and person-days lent only (with its place and the id to select it by).
        String body = shortlist(as(alice), draft, m1).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(body, "$.candidates");
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> team = (Map<String, Object>) row.get("team");
            assertThat(row.keySet()).containsExactlyInAnyOrder("position", "member", "name", "team", "skills",
                    "personDaysLent");
            assertThat(team.keySet()).containsExactlyInAnyOrder("id", "name");
        }
    }

    @Test
    @DisplayName("mat-shortlist-order: fewer person-days lent in the current Period come first, before the name")
    @Verifies("mat-shortlist-order")
    void theShortlistIsOrderedByPersonDaysLentThenName() throws Exception {
        // Run on the first Monday of next month, so E and E+1 (Wednesday and Thursday) lie inside the current Period.
        LocalDate monday = LocalDate.now(clock).plusMonths(1).withDayOfMonth(1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        movableClock.set(at(monday, 9));
        LocalDate e = monday.plusDays(2);
        testData().loan(world.dave().id(), operations, platform, e, e.plusDays(1));
        LocalDate d = dayD();

        long draft = createDraft(as(alice));
        long m1 = personLine(as(alice), draft, 1, d, d.plusDays(1), statistics);
        submit(as(alice), draft).andExpect(status().isOk());

        shortlist(as(alice), draft, m1)
                .andExpect(jsonPath("$.candidates[*].name", contains("Rita Gomez", "Dave Kim")))
                .andExpect(jsonPath("$.candidates[*].personDaysLent", contains(0, 2)));
    }

    @Test
    @DisplayName("mat-select: exactly the line's quantity is selected; a short Shortlist leaves the Request Waiting")
    @Verifies("mat-select")
    void theManagerSelectsExactlyTheLinesQuantity() throws Exception {
        LocalDate d = dayD();

        // 1. Bob: M2 (Statistics, 2): the Shortlist is Dave Kim only, so the Request is Waiting.
        long b1 = createDraft(as(bob));
        long m2 = personLine(as(bob), b1, 2, d, d.plusDays(1), statistics);
        submit(as(bob), b1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WAITING"));
        shortlist(as(bob), b1, m2).andExpect(jsonPath("$.candidates[*].name", contains("Dave Kim")));
        detail(as(bob), b1)
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("SHORTLIST_TOO_SHORT"));

        // 2. Alice: M1 (Statistics, 1); selecting both Candidates is LINE_NOT_ATOMIC.
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a1, m1, world.dave().id(), world.rita().id())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LINE_NOT_ATOMIC"));

        // 3. Carol Davis (Operations, no skills) is not on the Shortlist.
        select(as(alice), a1, m1, carolId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_A_CANDIDATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate WHERE line_id = ?", Integer.class, m1))
                .as("Candidates before the Manager's selection").isZero();

        // 4. Dave Kim: selected, an approval request is sent, and Dave is told he was selected.
        select(as(alice), a1, m1, world.dave().id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].candidates[*].name", contains("Dave Kim")))
                .andExpect(jsonPath("$.lines[0].candidates[0].state").value("OUTSTANDING"));
        assertThat(notificationsOf(world.dave())).containsExactly("CANDIDATE_SELECTED");
        assertThat(notificationsOf(world.oscar())).containsExactly("APPROVAL_REQUESTED");
    }

    @Test
    @DisplayName("mat-approve-route: the approval request goes to the Candidate's Manager, who decides")
    @Verifies("mat-approve-route")
    void theApprovalGoesToTheCandidatesManager() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a1).andExpect(status().isOk());
        select(as(alice), a1, m1, world.dave().id()).andExpect(status().isOk());
        long approval = approvalOf(m1);

        // 1. Oscar's Approvals: Dave Kim, for Platform, D to D+1, Earning 800.00 (EA).
        approvals(as(oscar))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) approval))
                .andExpect(jsonPath("$[0].candidateName").value("Dave Kim"))
                .andExpect(jsonPath("$[0].requestingTeam.id").value((int) platform))
                .andExpect(jsonPath("$[0].days.from").value(d.toString()))
                .andExpect(jsonPath("$[0].days.to").value(d.plusDays(1).toString()))
                .andExpect(jsonPath("$[0].earning").value(800.00))
                .andExpect(jsonPath("$[0].windowEndsAt").isNotEmpty());

        // 2. Alice, the requesting Manager, cannot decide.
        decide(as(alice), approval, "APPROVE", "")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 3. Oscar approves: recorded with actor, reason, and time; A1 becomes Allocated (its only line is approved).
        Instant now = clock.instant();
        decide(as(oscar), approval, "APPROVE", "Dave is free that week")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByName").value("Oscar Reed"))
                .andExpect(jsonPath("$.reason").value("Dave is free that week"));
        Map<String, Object> row = jdbc.queryForMap("SELECT state, decided_by, decided_at, reason FROM approval WHERE id = ?",
                approval);
        assertThat(row.get("state")).isEqualTo("APPROVED");
        assertThat(row.get("decided_by")).isEqualTo(world.oscar().id());
        assertThat(((Timestamp) row.get("decided_at")).toInstant()).isBetween(now.minusSeconds(60), now.plusSeconds(60));
        detail(as(alice), a1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[0].candidates[0].state").value("APPROVED"))
                .andExpect(jsonPath("$.lines[0].candidates[0].decidedByName").value("Oscar Reed"));

        // 4. A second decision is refused.
        decide(as(oscar), approval, "REFUSE", "Changed my mind")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));

        // Exactly one approval request was sent, to Oscar.
        assertThat(notificationsOf(world.oscar())).containsExactly("APPROVAL_REQUESTED");
        approvals(as(oscar)).andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("mat-own-team: a named Person of the requesting Team is refused with OWN_TEAM; no approval exists")
    @Verifies("mat-own-team")
    void noApprovalIsRoutedForTheRequestingTeamsOwnMember() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));

        // 1. M3 names Ben Ortiz of Platform.
        addNamedPersonLine(as(alice), draft, 1, d, d.plusDays(1), world.ben().id())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OWN_TEAM"));

        // 2. No approval request exists.
        approvals(as(alice)).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));

        // No line, approval request, or Loan of Ben for Platform.
        mvc.perform(get("/api/requests/{id}/draft", draft).with(as(alice))).andExpect(jsonPath("$.lines", empty()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate WHERE member_id = ?", Integer.class,
                world.ben().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE member_id = ?", Integer.class,
                world.ben().id())).isZero();
    }

    @Test
    @DisplayName("drf-line-options, steps 4 and 5: a named-Person line has quantity 1 and no Quote; quantity 2 is QUANTITY_INVALID")
    @Verifies(value = "drf-line-options", partial = true)
    void aPersonLineNamesOnePersonOrAsksForSkills() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));

        // 4. L3: Person, named Dave Kim, quantity 1, D to D+1.
        addNamedPersonLine(as(alice), draft, 1, d, d.plusDays(1), world.dave().id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocationClass").value("MATCHED"))
                .andExpect(jsonPath("$.namedMember").value((int) world.dave().id()))
                .andExpect(jsonPath("$.namedMemberName").value("Dave Kim"))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.days.from").value(d.toString()))
                .andExpect(jsonPath("$.days.to").value(d.plusDays(1).toString()))
                .andExpect(jsonPath("$.quote").doesNotExist());

        // 5. L3 again with quantity 2.
        addNamedPersonLine(as(alice), draft, 2, d, d.plusDays(1), world.dave().id())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));

        // A skill-based line asks for any quantity of 1 or more.
        addPersonLine(as(alice), draft, 2, d, d.plusDays(1), statistics, microscopy)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.skills[*].id", containsInAnyOrder((int) statistics, (int) microscopy)))
                .andExpect(jsonPath("$.namedMember").doesNotExist());
        addPersonLine(as(alice), draft, 0, d, d.plusDays(1), statistics)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));

        mvc.perform(get("/api/requests/{id}/draft", draft).with(as(alice)))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.requiredSum").value(0.00));
    }
}
