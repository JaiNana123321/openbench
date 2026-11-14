package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The test cases of issue #8 (docs/test-cases: Release, Profiles): the borrowing Manager ends a Loan
 * early, the lending Team's Earning is corrected to the Day Rate (400.00) times the days actually
 * lent, the correction alone may take its Balance below zero, which then blocks Requests with a
 * priced Required Exclusive or Metered line but not Person lines, and the Person is a Candidate again
 * from the day after the new last day. Step by step through the API, on the Matched data of the test
 * world.
 */
class LoanEndCasesTest extends MatchedWorldTest {

    @Test
    @DisplayName("rel-loan-prorate: ending a Loan early corrects the lending Team's Earning")
    @Verifies("rel-loan-prorate")
    void endingALoanEarlyCorrectsTheLendingTeamsEarning() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));
        assertThat(earnings(operations)).containsExactly(new BigDecimal("1200.00"));
        long original = originalEarning(formed.loan());

        // 1. End the Loan early, last day D: the Loan covers D only; Oscar is notified.
        endLoan(as(bob), formed.loan(), d)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) formed.loan()))
                .andExpect(jsonPath("$.days.from").value(d.toString()))
                .andExpect(jsonPath("$.days.to").value(d.toString()))
                .andExpect(jsonPath("$.daysLent").value(1))
                .andExpect(jsonPath("$.endedEarly").value(true));
        detail(as(bob), formed.request())
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[0].loans[0].days.to").value(d.toString()))
                .andExpect(jsonPath("$.lines[0].loans[0].daysLent").value(1))
                .andExpect(jsonPath("$.lines[0].loans[0].endedEarly").value(true));
        assertThat(notificationsOf(world.oscar())).contains("LOAN_ENDED");
        assertThat(notificationsOf(world.dave())).last().isEqualTo("LOAN_ENDED");

        // 2. Oscar's Statement: a correction of -800.00 referencing the original Earning; net 400.00; the
        // original unchanged.
        statement(oscar, operations)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].amount", contains(1200.00, -800.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].earnedPart", contains(1200.00, -800.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].loan",
                        contains((int) formed.loan(), (int) formed.loan())))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING' && @.amount < 0)].corrects",
                        contains((int) original)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING' && @.amount > 0)].corrects",
                        contains((Object) null)))
                .andExpect(jsonPath("$.totals.earnings").value(400.00));
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE id = ?", BigDecimal.class, original))
                .isEqualByComparingTo("1200.00");
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));

        // 3. Bob's Statement: no Refund or Charge was posted to Research for the Loan.
        statement(bob, research)
                .andExpect(jsonPath("$.transactions[?(@.kind == 'REFUND')]", empty()))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')]", empty()))
                .andExpect(jsonPath("$.account.balance").value(600.00));

        // Pass: Operations' Earning for the Loan is 400.00, the Day Rate times one day lent.
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM transaction WHERE kind = 'EARNING' AND loan_id = ?",
                BigDecimal.class, formed.loan())).isEqualByComparingTo("400.00");

        // The line's history records the early end, by Bob.
        assertThat(jdbc.queryForList("""
                SELECT note FROM request_event WHERE line_id = ? AND actor_id = ? AND note LIKE '%ended early%'""",
                String.class, formed.line(), world.bob().id()))
                .singleElement()
                .satisfies(note -> assertThat(note).contains("Earning correction -800.00 credits", "net 400.00"));
    }

    @Test
    @DisplayName("rel-loan-negative: an Earning correction may take a Balance below zero, and then blocks Exclusive and Metered Requests")
    @Verifies("rel-loan-negative")
    void anEarningCorrectionMayTakeABalanceBelowZero() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));
        // Oscar has Microscope 1 for D+7, D+8, and D+9, 09:00-17:00 (1440.00): Balance 760.00.
        long scope = createDraft(as(oscar));
        for (int day = 7; day <= 9; day++) {
            line(as(oscar), scope, microscope, 1, at(d.plusDays(day), 9), at(d.plusDays(day), 17), null);
        }
        submit(as(oscar), scope).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));
        assertThat(balance(operations)).isEqualByComparingTo("760.00");

        // 1. Bob ends the Loan early with last day D: Operations' Balance is -40.00.
        endLoan(as(bob), formed.loan(), d).andExpect(status().isOk());
        assertThat(balance(operations)).isEqualByComparingTo("-40.00");

        // 2. Submit O1 (Room A, D+12 10:00-12:00, Required, 30.00): refused with INSUFFICIENT_BALANCE.
        long o1Request = createDraft(as(oscar));
        line(as(oscar), o1Request, room, 1, at(d.plusDays(12), 10), at(d.plusDays(12), 12), roomA);
        submit(as(oscar), o1Request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.shortfall").value(70.00));
        detail(as(oscar), o1Request).andExpect(jsonPath("$.request.state").value("DRAFT"));

        // 3. Submit O2 (Person, Java, 1, D+14 to D+15, the only line): accepted, a Request of Matched lines costs nothing.
        long o2Request = createDraft(as(oscar));
        personLine(as(oscar), o2Request, 1, d.plusDays(14), d.plusDays(15), java);
        submit(as(oscar), o2Request).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        assertThat(balance(operations)).isEqualByComparingTo("-40.00");

        // 4. Erin posts a mid-Period Grant of 100.00 to Operations: Balance 60.00.
        mvc.perform(post("/api/ledger/grants").with(as(erin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": " + operations + ", \"amount\": 100.00, \"reason\": \"Correction cover\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(60.00));

        // 5. Submit O1 again: Allocated; Balance 30.00.
        submit(as(oscar), o1Request).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));
        assertThat(balance(operations)).isEqualByComparingTo("30.00");

        // Pass: only the Earning correction took the Balance below zero.
        assertThat(jdbc.queryForList("SELECT kind FROM transaction WHERE team_id = ? AND amount < 0", String.class,
                operations)).containsExactly("EARNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE team_id = ? AND corrects_id IS NOT NULL",
                Integer.class, operations)).isEqualTo(1);
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    @Test
    @DisplayName("prf-loan-overlap: a Member with an overlapping Loan is not a Candidate, and a Loan leaves availability unchanged")
    @Verifies("prf-loan-overlap")
    void aMemberWithAnOverlappingLoanIsNotACandidate() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(1));

        // 1. Dave's profile: availability today to D+60, and the Loan to Research for D to D+1.
        myProfile(dave)
                .andExpect(jsonPath("$.availability", hasSize(1)))
                .andExpect(jsonPath("$.availability[0].from").value(LocalDate.now(clock).toString()))
                .andExpect(jsonPath("$.availability[0].to").value(d.plusDays(60).toString()));
        mvc.perform(get("/api/members/me/loans").with(as(dave)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].borrowingTeam.id").value((int) research))
                .andExpect(jsonPath("$[0].days.from").value(d.toString()))
                .andExpect(jsonPath("$[0].days.to").value(d.plusDays(1).toString()));

        // 2. Alice's M1 (Statistics, D+1 to D+2): Rita Gomez only, since Dave's Loan overlaps D+1.
        long first = createDraft(as(alice));
        long m1 = personLine(as(alice), first, 1, d.plusDays(1), d.plusDays(2), statistics);
        submit(as(alice), first).andExpect(status().isOk());
        shortlist(as(alice), first, m1).andExpect(jsonPath("$.candidates[*].name", contains("Rita Gomez")));

        // 3. M2 (D+2 to D+3): Dave Kim and Rita Gomez are both Candidates.
        long second = createDraft(as(alice));
        long m2 = personLine(as(alice), second, 1, d.plusDays(2), d.plusDays(3), statistics);
        submit(as(alice), second).andExpect(status().isOk());
        shortlist(as(alice), second, m2)
                .andExpect(jsonPath("$.candidates[*].name", containsInAnyOrder("Dave Kim", "Rita Gomez")));

        // 4. Bob ends Dave's Loan early with last day D: it covers D only; Dave's availability is unchanged.
        endLoan(as(bob), formed.loan(), d)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.from").value(d.toString()))
                .andExpect(jsonPath("$.days.to").value(d.toString()));
        mvc.perform(get("/api/members/me/loans").with(as(dave)))
                .andExpect(jsonPath("$[0].days.to").value(d.toString()))
                .andExpect(jsonPath("$[0].endedEarly").value(true));
        myProfile(dave)
                .andExpect(jsonPath("$.availability", hasSize(1)))
                .andExpect(jsonPath("$.availability[0].from").value(LocalDate.now(clock).toString()))
                .andExpect(jsonPath("$.availability[0].to").value(d.plusDays(60).toString()));

        // 5. A new Request with M1: Dave Kim is a Candidate again, free from D+1.
        long third = createDraft(as(alice));
        long m1Again = personLine(as(alice), third, 1, d.plusDays(1), d.plusDays(2), statistics);
        submit(as(alice), third).andExpect(status().isOk());
        shortlist(as(alice), third, m1Again)
                .andExpect(jsonPath("$.candidates[*].name", containsInAnyOrder("Dave Kim", "Rita Gomez")));

        // Pass: Dave was never in two overlapping Loans, and his availability never changed.
        assertThat(jdbc.queryForList("SELECT ends_on FROM loan WHERE member_id = ?", Date.class,
                world.dave().id())).containsExactly(Date.valueOf(d));
    }

    @Test
    @DisplayName("A Request of Person lines only is allocated, and its Loan formed, while the Balance is below zero")
    @Verifies(value = "rel-loan-negative", partial = true)
    void aRequestOfPersonLinesOnlyIsAllocatedBelowZero() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));
        long scope = createDraft(as(oscar));
        for (int day = 7; day <= 9; day++) {
            line(as(oscar), scope, microscope, 1, at(d.plusDays(day), 9), at(d.plusDays(day), 17), null);
        }
        submit(as(oscar), scope).andExpect(jsonPath("$.state").value("ALLOCATED"));
        endLoan(as(bob), formed.loan(), d).andExpect(status().isOk());
        assertThat(balance(operations)).isEqualByComparingTo("-40.00");

        // O2 with an Optional priced line: the Person line is accepted, the Optional line is Dropped at allocation.
        long o2Request = createDraft(as(oscar));
        long o2 = personLine(as(oscar), o2Request, 1, d.plusDays(14), d.plusDays(15), java);
        long optional = line(as(oscar), o2Request, room, 1, at(d.plusDays(14), 10), at(d.plusDays(14), 12), roomB);
        markOptional(as(oscar), o2Request, optional);
        submit(as(oscar), o2Request).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(oscar), o2Request, o2, world.ben().id()).andExpect(status().isOk());

        // Alice approves Ben: the Request is Allocated with no Charge, Ben's Loan forms, Platform earns.
        decide(as(alice), approvalOf(o2), "APPROVE", "").andExpect(status().isOk());
        detail(as(oscar), o2Request)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("DROPPED"));
        assertThat(balance(operations)).isEqualByComparingTo("-40.00");
        assertThat(earnings(platform)).containsExactly(new BigDecimal("800.00"));
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    // ---------------------------------------------------------------- helpers

    /** A Request of Bob's, Allocated, with one Person line on which Dave Kim is lent to Research for the days. */
    protected record Formed(long request, long line, long loan) {}

    protected Formed loanOfDave(LocalDate from, LocalDate to) throws Exception {
        long request = createDraft(as(bob));
        long line = personLine(as(bob), request, 1, from, to, statistics);
        submit(as(bob), request).andExpect(status().isOk());
        select(as(bob), request, line, world.dave().id()).andExpect(status().isOk());
        decide(as(oscar), approvalOf(line), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), request).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        long loan = jdbc.queryForObject("SELECT id FROM loan WHERE line_id = ?", Long.class, line);
        return new Formed(request, line, loan);
    }

    protected ResultActions endLoan(RequestPostProcessor caller, long loan, LocalDate newEnd) throws Exception {
        return mvc.perform(post("/api/loans/{id}/end", loan).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newEnd\": \"" + newEnd + "\"}"));
    }

    protected ResultActions statement(String manager, long team) throws Exception {
        return mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(as(manager)));
    }

    protected ResultActions myProfile(String member) throws Exception {
        return mvc.perform(get("/api/members/me/profile").with(as(member)));
    }

    protected long originalEarning(long loan) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE loan_id = ? AND corrects_id IS NULL", Long.class,
                loan);
    }

    /** The Team's Earnings, corrections included, oldest first. */
    protected List<BigDecimal> earnings(long team) {
        return jdbc.queryForList("SELECT amount FROM transaction WHERE team_id = ? AND kind = 'EARNING' ORDER BY id",
                BigDecimal.class, team);
    }

    protected BigDecimal balance(long team) {
        return ledger.account(new TeamId(team)).balance().amount();
    }
}
