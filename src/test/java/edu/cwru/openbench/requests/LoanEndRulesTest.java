package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The rules of an early Loan end (design Section 4.8, {@code endLoanEarly}): only the borrowing
 * Manager may end a Loan, to a new last day on or after its first, before its current last, and not
 * in the past; a refusal changes nothing. A Loan can be ended earlier more than once, and a day given
 * back that is not a Monday-to-Friday day corrects nothing.
 */
@DisplayName("Release.Loan.Prorate: the rules of an early Loan end")
class LoanEndRulesTest extends LoanEndWorldTest {

    @Test
    @DisplayName("LOAN_END_INVALID: the current last day, a later one, a day before the first, or a day in the past")
    void refusesAnInvalidNewEnd() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));

        for (LocalDate invalid : new LocalDate[] {d.plusDays(2), d.plusDays(3), d.minusDays(1)}) {
            endLoan(as(bob), formed.loan(), invalid)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("LOAN_END_INVALID"));
        }
        assertUnchanged(formed, d, d.plusDays(2));

        // On D+1, D is in the past: the Loan cannot end on it, but D+1 itself is fine.
        movableClock.set(at(d.plusDays(1), 9));
        endLoan(as(bob), formed.loan(), d)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_END_INVALID"))
                .andExpect(jsonPath("$.today").value(d.plusDays(1).toString()));
        assertUnchanged(formed, d, d.plusDays(2));
        endLoan(as(bob), formed.loan(), d.plusDays(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.to").value(d.plusDays(1).toString()));
    }

    @Test
    @DisplayName("FORBIDDEN: the lending Manager and a third Team's Manager; a Capacity Planner lacks the role")
    void onlyTheBorrowingManagerEndsALoan() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));

        endLoan(as(oscar), formed.loan(), d)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        endLoan(as(alice), formed.loan(), d)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        endLoan(as(erin), formed.loan(), d).andExpect(status().isForbidden());
        endLoan(as(dave), formed.loan(), d).andExpect(status().isForbidden());
        assertUnchanged(formed, d, d.plusDays(2));
    }

    @Test
    @DisplayName("NOT_FOUND: an unknown Loan; VALIDATION_FAILED: no new end")
    void refusesAnUnknownLoanOrAMissingEnd() throws Exception {
        endLoan(as(bob), Long.MAX_VALUE, dayD()).andExpect(status().isNotFound());
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));
        mvc.perform(post("/api/loans/{id}/end", formed.loan()).with(as(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertUnchanged(formed, d, d.plusDays(2));
    }

    @Test
    @DisplayName("A Loan ended early can end earlier still; each correction names the original Earning")
    void aLoanCanEndEarlierTwice() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));
        long original = originalEarning(formed.loan());

        endLoan(as(bob), formed.loan(), d.plusDays(1)).andExpect(status().isOk()).andExpect(jsonPath("$.daysLent").value(2));
        endLoan(as(bob), formed.loan(), d).andExpect(status().isOk()).andExpect(jsonPath("$.daysLent").value(1));
        endLoan(as(bob), formed.loan(), d)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_END_INVALID"));

        statement(oscar, operations)
                .andExpect(jsonPath("$.transactions[?(@.kind == 'EARNING')].amount", contains(1200.00, -400.00, -400.00)))
                .andExpect(jsonPath("$.transactions[?(@.amount < 0)].corrects", contains((int) original, (int) original)))
                .andExpect(jsonPath("$.totals.earnings").value(400.00));
        assertThat(notificationsOf(world.oscar()).stream().filter("LOAN_ENDED"::equals)).hasSize(2);
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    @Test
    @DisplayName("Giving back only a weekend day shortens the Loan but posts no correction")
    void aWeekendDayCorrectsNothing() throws Exception {
        // Friday D+2 to Sunday D+4: one person-day (Friday). Ending on Saturday keeps that day.
        LocalDate friday = dayD().plusDays(2);
        Formed formed = loanOfDave(friday, friday.plusDays(2));
        assertThat(earnings(operations)).containsExactly(new BigDecimal("400.00"));

        endLoan(as(bob), formed.loan(), friday.plusDays(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.to").value(friday.plusDays(1).toString()))
                .andExpect(jsonPath("$.daysLent").value(1))
                .andExpect(jsonPath("$.endedEarly").value(true));
        assertThat(earnings(operations)).containsExactly(new BigDecimal("400.00"));
        assertThat(notificationsOf(world.oscar())).contains("LOAN_ENDED");
    }

    @Test
    @DisplayName("The rate is the original Earning's, not today's Day Rate")
    void theRateIsTheOriginalEarnings() throws Exception {
        LocalDate d = dayD();
        Formed formed = loanOfDave(d, d.plusDays(2));
        String dayRate = jdbc.queryForObject("SELECT value FROM planner_setting WHERE key = 'day_rate'", String.class);
        try {
            jdbc.update("UPDATE planner_setting SET value = '1000.00' WHERE key = 'day_rate'");
            endLoan(as(bob), formed.loan(), d).andExpect(status().isOk());
        } finally {
            jdbc.update("UPDATE planner_setting SET value = ? WHERE key = 'day_rate'", dayRate);
        }
        assertThat(earnings(operations)).containsExactly(new BigDecimal("1200.00"), new BigDecimal("-800.00"));
    }

    private void assertUnchanged(Formed formed, LocalDate from, LocalDate to) {
        assertThat(jdbc.queryForMap("SELECT starts_on, ends_on, days_lent, ended_early FROM loan WHERE id = ?",
                formed.loan()))
                .containsEntry("starts_on", Date.valueOf(from))
                .containsEntry("ends_on", Date.valueOf(to))
                .containsEntry("ended_early", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE loan_id = ? AND corrects_id IS NOT NULL",
                Integer.class, formed.loan())).isZero();
        assertThat(notificationsOf(world.oscar())).doesNotContain("LOAN_ENDED");
    }
}
