package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.RequestId;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The test cases of issue #6 (docs/test-cases: Matched Allocation, Lifecycle, Ledger): the last
 * Required approval allocates the Request in one step, each Approved Candidate becomes a Loan, and
 * the lending Team earns the Day Rate (400.00) per Monday-to-Friday day of it. Step by step through
 * the API, on the Matched data of the test world.
 */
class LoanCasesTest extends MatchedWorldTest {

    @Autowired
    private RequestLifecycleService lifecycle;

    @Test
    @DisplayName("mat-loan-form: an approved Candidate becomes a Loan and the lending Team earns")
    @Verifies("mat-loan-form")
    void anApprovedCandidateBecomesALoanAndTheLendingTeamEarns() throws Exception {
        LocalDate d = dayD();

        // 1. Bob's B1 with M1 (Statistics, 1, D to D+1, Required), Dave Kim selected: Pending; Dave is told.
        long b1 = createDraft(as(bob));
        long m1 = personLine(as(bob), b1, 1, d, d.plusDays(1), statistics);
        submit(as(bob), b1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(bob), b1, m1, world.dave().id()).andExpect(status().isOk());
        detail(as(bob), b1).andExpect(jsonPath("$.request.state").value("PENDING"));
        assertThat(notificationsOf(world.dave())).containsExactly("CANDIDATE_SELECTED");

        // 2. Oscar approves: B1 is Allocated; a Loan of Dave Kim to Research for D to D+1.
        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[0].loans", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].loans[0].memberName").value("Dave Kim"))
                .andExpect(jsonPath("$.lines[0].loans[0].lendingTeam.id").value((int) operations))
                .andExpect(jsonPath("$.lines[0].loans[0].borrowingTeam.id").value((int) research))
                .andExpect(jsonPath("$.lines[0].loans[0].days.from").value(d.toString()))
                .andExpect(jsonPath("$.lines[0].loans[0].days.to").value(d.plusDays(1).toString()))
                .andExpect(jsonPath("$.lines[0].loans[0].daysLent").value(2));
        long loan = loanOf(m1);

        // 3. Operations earns 800.00 (2 person-days x 400.00) for B1's line; Research has no Charge for M1.
        statement(oscar, operations)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].amount", contains(800.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].earnedPart", contains(800.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].line", contains((int) m1)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].loan", contains((int) loan)))
                .andExpect(jsonPath("$.totals.earnings").value(800.00))
                .andExpect(jsonPath("$.account.earned").value(800.00));
        statement(bob, research).andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')]", empty()));
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));

        // 4. Dave: a notice that the Loan formed, and the Loan on My profile, with nothing to accept or decline.
        assertThat(notificationsOf(world.dave())).containsExactly("CANDIDATE_SELECTED", "LOAN_FORMED");
        String body = mvc.perform(get("/api/members/me/loans").with(as(dave)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].borrowingTeam.id").value((int) research))
                .andExpect(jsonPath("$[0].days.from").value(d.toString()))
                .andExpect(jsonPath("$[0].days.to").value(d.plusDays(1).toString()))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> loans = JsonPath.read(body, "$");
        assertThat(loans.getFirst().keySet()).containsExactlyInAnyOrder("id", "line", "member", "memberName",
                "lendingTeam", "borrowingTeam", "days", "daysLent", "endedEarly");

        // One Loan and one Earning of 800.00 for B1.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE line_id = ?", Integer.class, m1)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT amount FROM transaction WHERE kind = 'EARNING' AND loan_id = ?",
                BigDecimal.class, loan)).containsExactly(new BigDecimal("800.00"));
    }

    @Test
    @DisplayName("mat-loan-optional-later: an Optional Matched line approved after allocation forms its Loan then")
    @Verifies("mat-loan-optional-later")
    void anOptionalLineApprovedAfterAllocationFormsItsLoanThen() throws Exception {
        LocalDate d = dayD();

        // 1. B1: M1 (Statistics, Required) and M6 (Ben Ortiz, Optional); Dave selected. Approvals to Oscar and Alice.
        long b1 = createDraft(as(bob));
        long m1 = personLine(as(bob), b1, 1, d, d.plusDays(1), statistics);
        long m6 = id(addNamedPersonLine(as(bob), b1, 1, d, d.plusDays(1), world.ben().id()));
        markOptional(as(bob), b1, m6);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(bob), b1, m1, world.dave().id()).andExpect(status().isOk());
        assertThat(notificationsOf(world.oscar())).containsExactly("APPROVAL_REQUESTED");
        assertThat(notificationsOf(world.alice())).containsExactly("APPROVAL_REQUESTED");

        // 2. Oscar approves Dave: B1 is Allocated, Dave's Loan forms, Operations earns 800.00; M6 still awaits.
        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[1].loans", empty()));
        assertThat(earnings(operations)).containsExactly(new BigDecimal("800.00"));
        assertThat(earnings(platform)).as("Platform's Earning before Ben's Loan formed").isEmpty();

        // 3. Alice approves Ben: his Loan to Research forms now, and Platform earns 800.00.
        decide(as(alice), approvalOf(m6), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].loans[0].memberName").value("Ben Ortiz"))
                .andExpect(jsonPath("$.lines[1].loans[0].borrowingTeam.id").value((int) research))
                .andExpect(jsonPath("$.lines[1].loans[0].days.from").value(d.toString()))
                .andExpect(jsonPath("$.lines[1].loans[0].days.to").value(d.plusDays(1).toString()));
        assertThat(earnings(platform)).containsExactly(new BigDecimal("800.00"));
        assertThat(earnings(operations)).containsExactly(new BigDecimal("800.00"));
        assertThat(notificationsOf(world.ben())).containsExactly("CANDIDATE_SELECTED", "LOAN_FORMED");

        // Each Earning posted when its Loan formed, not before.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM transaction t JOIN loan l ON l.id = t.loan_id
                WHERE l.line_id IN (?, ?) AND t.posted_at >= l.created_at""", Integer.class, m1, m6)).isEqualTo(2);
    }

    @Test
    @DisplayName("lif-allocate: when the last approval arrives, everything commits in one step")
    @Verifies("lif-allocate")
    void theLastApprovalCommitsEverythingInOneStep() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        long l1 = line(as(alice), a1, room, 1, at(d, 10), at(d, 12), roomA);
        long l2 = line(as(alice), a1, gpu, 4, at(d, 10), at(d, 12), null);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a1, m1, world.dave().id()).andExpect(status().isOk());
        assertThat(account(platform)).containsEntry("held", new BigDecimal("90.00"));

        // 1. Oscar approves Dave Kim: A1 is Allocated.
        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        detail(as(alice), a1).andExpect(jsonPath("$.request.state").value("ALLOCATED"));

        // 2. Alice: L1 and L2 Allocated; Charges 30.00 and 60.00; held 0.00; Balance 4910.00; Alice notified.
        detail(as(alice), a1)
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[2].line.state").value("ALLOCATED"));
        statement(alice, platform)
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].amount", contains(30.00, 60.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].line", contains((int) l1, (int) l2)))
                .andExpect(jsonPath("$.account.held").value(0.00))
                .andExpect(jsonPath("$.account.balance").value(4910.00));
        assertThat(jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = 'REQUEST_UPDATE'",
                String.class, world.alice().id())).anySatisfy(text -> assertThat(text).contains("is Allocated"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE line_id IN (?, ?)", Integer.class, l1, l2))
                .isZero();
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));

        // 3. Operations: an Earning of 800.00; Dave's Loan to Platform for D to D+1.
        statement(oscar, operations)
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].amount", contains(800.00)));
        assertThat(jdbc.queryForMap("SELECT member_id, lending_team_id, borrowing_team_id, starts_on, ends_on, days_lent"
                + " FROM loan WHERE line_id = ?", m1))
                .containsEntry("member_id", world.dave().id())
                .containsEntry("lending_team_id", operations)
                .containsEntry("borrowing_team_id", platform)
                .containsEntry("starts_on", Date.valueOf(d))
                .containsEntry("ends_on", Date.valueOf(d.plusDays(1)))
                .containsEntry("days_lent", 2);

        // The Holds, Charges, Loan, Earning, and state change appear together, at one instant.
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT at) FROM request_event
                WHERE request_id = ? AND (to_state IN ('ALLOCATED', 'LOANED'))""", Integer.class, a1)).isEqualTo(1);
    }

    @Test
    @DisplayName("led-earning: an Earning counts only Monday-to-Friday days of the Loan")
    @Verifies("led-earning")
    void anEarningCountsOnlyWeekdays() throws Exception {
        LocalDate friday = dayD().plusDays(2);
        LocalDate monday = dayD().plusDays(5);

        // 1. M1 from D+2 (Friday) to D+5 (Monday), Dave selected and approved: Allocated, a Loan for D+2 to D+5.
        long b1 = createDraft(as(bob));
        long m1 = personLine(as(bob), b1, 1, friday, monday, statistics);
        submit(as(bob), b1).andExpect(status().isOk());
        select(as(bob), b1, m1, world.dave().id()).andExpect(status().isOk());
        decide(as(oscar), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].loans[0].days.from").value(friday.toString()))
                .andExpect(jsonPath("$.lines[0].loans[0].days.to").value(monday.toString()))
                .andExpect(jsonPath("$.lines[0].loans[0].daysLent").value(2));

        // 2. Oscar's Statement: 800.00, two person-days (Friday and Monday), not 1600.00.
        statement(oscar, operations)
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].amount", contains(800.00)));
    }

    @Test
    @DisplayName("lif-optional-open: an Optional line's approval never holds a Request in Pending, nor is lost at the lapse")
    @Verifies("lif-optional-open")
    void anOptionalApprovalNeverHoldsARequestInPending() throws Exception {
        LocalDate d = dayD();

        // 1. B1 with L1 (Room A, D 10:00-12:00, Required) and M6 (Ben Ortiz, Optional): Allocated at once.
        long b1 = createDraft(as(bob));
        long l1 = line(as(bob), b1, room, 1, at(d, 10), at(d, 12), roomA);
        long m6 = id(addNamedPersonLine(as(bob), b1, 1, d, d.plusDays(1), world.ben().id()));
        markOptional(as(bob), b1, m6);
        String submitted = submit(as(bob), b1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ALLOCATED"))
                .andReturn().getResponse().getContentAsString();
        Instant submittedAt = Instant.parse(JsonPath.read(submitted, "$.submittedAt"));
        detail(as(bob), b1)
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.history[*].toState", not(hasItem("PENDING"))));
        approvals(as(alice))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].candidateName").value("Ben Ortiz"));
        assertThat(jdbc.queryForObject("SELECT window_ends_at FROM request WHERE id = ?", Timestamp.class, b1)
                .toInstant()).isEqualTo(ObjectionWindow.endsAt(submittedAt, 2, clock.getZone()));

        // 2. Room A 10:00-12:00 allocated to Research, B1, not on Hold; one Charge of 30.00; held 0.00.
        assertThat(jdbc.queryForList("""
                SELECT state, team_id, request_id FROM exclusive_occurrence
                WHERE line_id = ? AND state IN ('HELD', 'ALLOCATED')""", l1))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("state", "ALLOCATED").containsEntry("team_id", research)
                        .containsEntry("request_id", b1));
        statement(bob, research)
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].amount", contains(30.00)))
                .andExpect(jsonPath("$.account.held").value(0.00));

        // 3. [clock] The window lapses with Alice silent: M6's approval is escalated and Erin is told; B1 stays
        // Allocated and L1 keeps its allocation.
        long erinId = jdbc.queryForObject("SELECT id FROM member WHERE username = ?", Long.class, erin);
        movableClock.set(ObjectionWindow.endsAt(submittedAt, 2, clock.getZone()).plusSeconds(60));
        assertThat(lifecycle.lapsedWindows(clock.instant())).contains(new RequestId(b1));
        lifecycle.onWindowLapse(new RequestId(b1));
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[1].candidates[0].escalated").value(true));
        assertThat(jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = 'ESCALATED'",
                String.class, erinId)).anySatisfy(text -> assertThat(text).contains("Request " + b1 + " is escalated"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exclusive_occurrence WHERE line_id = ? AND state = 'ALLOCATED'""", Integer.class, l1))
                .isEqualTo(1);

        // 4. Erin approves Ben from Escalations: his Loan to Research forms now; Platform earns 800.00; Ben and
        // Bob are told.
        decide(as(erin), approvalOf(m6), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].loans[0].memberName").value("Ben Ortiz"))
                .andExpect(jsonPath("$.lines[1].loans[0].borrowingTeam.id").value((int) research))
                .andExpect(jsonPath("$.lines[1].loans[0].days.from").value(d.toString()))
                .andExpect(jsonPath("$.lines[1].loans[0].days.to").value(d.plusDays(1).toString()));
        assertThat(earnings(platform)).containsExactly(new BigDecimal("800.00"));
        assertThat(notificationsOf(world.ben())).containsExactly("CANDIDATE_SELECTED", "LOAN_FORMED");
        assertThat(jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = 'REQUEST_UPDATE'",
                String.class, world.bob().id())).anySatisfy(text -> assertThat(text).contains("has its Loans"));

        // L1 was never held or delayed, and M6 was neither dropped at allocation nor lost at the lapse.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_event WHERE line_id = ? AND to_state = 'HELD'",
                Integer.class, l1)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_event WHERE line_id = ? AND to_state = 'DROPPED'",
                Integer.class, m6)).isZero();
    }

    @Test
    @DisplayName("Worked example (design Section 4.8): two Loans of 30 days post two Earnings of 30 person-days")
    void twoLoansOfThirtyDaysPostTwoEarnings() throws Exception {
        // Six weeks, Monday to Friday: Monday D+5 to Friday D+44, 30 weekdays; Dave and Rita are available.
        LocalDate monday = dayD().plusDays(5);
        LocalDate friday = monday.plusDays(39);
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 2, monday, friday, statistics);
        line(as(alice), a1, room, 1, at(dayD(), 10), at(dayD(), 12), roomA);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a1, m1, world.dave().id(), world.rita().id()).andExpect(status().isOk());
        List<Long> approvals = jdbc.queryForList("""
                SELECT a.id FROM approval a JOIN candidate c ON c.id = a.candidate_id WHERE c.line_id = ? ORDER BY a.id""",
                Long.class, m1);

        // The first approval leaves A1 Pending; the second allocates it.
        decide(as(oscar), approvals.get(0), "APPROVE", "").andExpect(status().isOk());
        detail(as(alice), a1).andExpect(jsonPath("$.request.state").value("PENDING"));
        decide(as(bob), approvals.get(1), "APPROVE", "").andExpect(status().isOk());
        detail(as(alice), a1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].loans[*].daysLent", contains(30, 30)))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));
        assertThat(earnings(operations)).containsExactly(new BigDecimal("12000.00"));
        assertThat(earnings(research)).containsExactly(new BigDecimal("12000.00"));
        statement(alice, platform).andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].amount", contains(30.00)));
    }

    // ---------------------------------------------------------------- helpers

    protected ResultActions statement(String manager, long team) throws Exception {
        return mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(as(manager)));
    }

    /** The id of the only Loan the line formed. */
    protected long loanOf(long line) {
        return jdbc.queryForObject("SELECT id FROM loan WHERE line_id = ?", Long.class, line);
    }

    /** The Team's Earnings, oldest first. */
    protected List<BigDecimal> earnings(long team) {
        return jdbc.queryForList("SELECT amount FROM transaction WHERE team_id = ? AND kind = 'EARNING' ORDER BY id",
                BigDecimal.class, team);
    }
}
