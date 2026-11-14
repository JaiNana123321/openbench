package edu.cwru.openbench.ledger;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.LoanId;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link LedgerService#correctEarning}: the Earning correction of an early Loan end (SRS
 * Release.Loan.Prorate), the one Transaction that may take a Balance below zero, and only it.
 */
@DisplayName("Release.Loan.Prorate: LedgerService.correctEarning")
class LedgerCorrectionTest extends PostgresTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Clock clock;

    private long period;
    private long lending;
    private TeamId lendingTeam;
    private LoanId loan;
    private long line;

    @BeforeEach
    void createALoanWithItsEarning() {
        period = testData().periodContaining(LocalDate.now(clock));
        lending = testData().team(unique("Operations"));
        lendingTeam = new TeamId(lending);
        long borrowing = testData().team(unique("Research"));
        long dave = testData().member(lending, unique("dave"));
        LocalDate wednesday = LocalDate.of(2190, 1, 4);
        loan = new LoanId(testData().loan(dave, lending, borrowing, wednesday, wednesday.plusDays(2)));
        line = jdbc.queryForObject("SELECT line_id FROM loan WHERE id = ?", Long.class, loan.value());
        ledger.postEarning(new EarningSpec(lendingTeam, loan, new RequestLineId(line), Credits.of("1200.00"),
                "Dave Kim to Research, 3 person-days"), null);
    }

    @Test
    @DisplayName("A correction is a negative Earning naming the original, which stays unchanged")
    @Verifies(value = "rel-loan-prorate", partial = true)
    void aCorrectionIsANegativeEarningNamingTheOriginal() {
        long original = originalEarning();

        ledger.correctEarning(lendingTeam, loan, Credits.of("-800.00"), "Correction: 2 person-days no longer lent");

        assertThat(jdbc.queryForMap("""
                SELECT amount, granted_part, earned_part, loan_id, line_id, corrects_id, reason FROM transaction
                WHERE kind = 'EARNING' AND team_id = ? AND corrects_id IS NOT NULL""", lending))
                .containsEntry("amount", new BigDecimal("-800.00"))
                .containsEntry("granted_part", new BigDecimal("0.00"))
                .containsEntry("earned_part", new BigDecimal("-800.00"))
                .containsEntry("loan_id", loan.value())
                .containsEntry("line_id", line)
                .containsEntry("corrects_id", original)
                .containsEntry("reason", "Correction: 2 person-days no longer lent");
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE id = ?", BigDecimal.class, original))
                .isEqualByComparingTo("1200.00");
        assertThat(ledger.netEarning(loan)).isEqualTo(Credits.of("400.00"));
        assertThat(ledger.account(lendingTeam).earned()).isEqualTo(Credits.of("400.00"));
        assertThat(account()).isEqualTo(foldOfTransactions());
    }

    @Test
    @DisplayName("Only the correction takes the Balance below zero; then no positive sum is affordable, nothing is")
    @Verifies(value = "rel-loan-negative", partial = true)
    void onlyTheCorrectionTakesTheBalanceBelowZero() {
        // Earned 1200.00 and granted 200.00, less a Charge of 640.00: Balance 760.00, as in the case.
        ledger.postGrant(lendingTeam, new PeriodId(period), Credits.of("200.00"), null, null);
        RequestLineId spent = line();
        ledger.holdCredits(lendingTeam, spent, Credits.of("640.00"));
        ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(lendingTeam, spent, Credits.of("640.00"))));
        assertThat(ledger.account(lendingTeam).balance()).isEqualTo(Credits.of("760.00"));

        // A Charge, a Hold, or an early refusal never goes below zero: each refuses the shortfall instead.
        assertRefused(() -> ledger.requireAffordable(lendingTeam, Credits.of("760.01")), "0.01");
        RequestLineId more = line();
        assertRefused(() -> ledger.holdCredits(lendingTeam, more, Credits.of("800.00")), "40.00");
        assertRefused(() -> ledger.commitCharges(new RequestId(1),
                List.of(new ChargeSpec(lendingTeam, more, Credits.of("800.00")))), "40.00");
        assertThat(ledger.account(lendingTeam).balance()).isEqualTo(Credits.of("760.00"));

        // The correction does.
        ledger.correctEarning(lendingTeam, loan, Credits.of("-800.00"), "Correction");
        assertThat(ledger.account(lendingTeam).balance()).isEqualTo(Credits.of("-40.00"));
        assertThat(ledger.account(lendingTeam).available()).isEqualTo(Credits.of("-40.00"));
        assertThat(account()).isEqualTo(foldOfTransactions());

        // Below zero: any positive sum is short by itself plus 40.00; nothing to pay is still accepted.
        assertRefused(() -> ledger.requireAffordable(lendingTeam, Credits.of("30.00")), "70.00");
        assertRefused(() -> ledger.holdCredits(lendingTeam, more, Credits.of("30.00")), "70.00");
        assertRefused(() -> ledger.commitCharges(new RequestId(1),
                List.of(new ChargeSpec(lendingTeam, more, Credits.of("30.00")))), "70.00");
        ledger.requireAffordable(lendingTeam, Credits.ZERO);
        ledger.commitCharges(new RequestId(1), List.of());
        assertThat(ledger.account(lendingTeam).balance()).isEqualTo(Credits.of("-40.00"));

        // A Grant restores it.
        ledger.postGrant(lendingTeam, new PeriodId(period), Credits.of("100.00"), "Correction cover", null);
        ledger.requireAffordable(lendingTeam, Credits.of("60.00"));
        assertThat(ledger.account(lendingTeam).balance()).isEqualTo(Credits.of("60.00"));
    }

    @Test
    @DisplayName("Two corrections of one Loan both name the original Earning and leave its net Earning at zero or more")
    void twoCorrectionsNameTheOriginal() {
        long original = originalEarning();
        ledger.correctEarning(lendingTeam, loan, Credits.of("-400.00"), "Correction one");
        ledger.correctEarning(lendingTeam, loan, Credits.of("-400.00"), "Correction two");

        assertThat(jdbc.queryForList("SELECT corrects_id FROM transaction WHERE loan_id = ? AND corrects_id IS NOT NULL",
                Long.class, loan.value())).containsExactly(original, original);
        assertThat(ledger.netEarning(loan)).isEqualTo(Credits.of("400.00"));

        assertThatThrownBy(() -> ledger.correctEarning(lendingTeam, loan, Credits.of("-400.01"), "Too much"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.netEarning(loan)).isEqualTo(Credits.of("400.00"));
    }

    @Test
    @DisplayName("A correction must be negative, of a Loan with an Earning, for its lending Team")
    void refusesAnInvalidCorrection() {
        assertThatThrownBy(() -> ledger.correctEarning(lendingTeam, loan, Credits.of("100.00"), "Positive"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.correctEarning(lendingTeam, loan, Credits.ZERO, "Zero"))
                .isInstanceOf(IllegalArgumentException.class);
        TeamId other = new TeamId(testData().team(unique("Platform")));
        assertThatThrownBy(() -> ledger.correctEarning(other, loan, Credits.of("-100.00"), "Other Team"))
                .isInstanceOf(IllegalStateException.class);

        long dave = testData().member(lending, unique("dave"));
        LocalDate monday = LocalDate.of(2190, 1, 9);
        LoanId unearned = new LoanId(testData().loan(dave, lending, testData().team(unique("Research")), monday, monday));
        assertThatThrownBy(() -> ledger.correctEarning(lendingTeam, unearned, Credits.of("-100.00"), "No Earning"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.netEarning(unearned)).isEqualTo(Credits.ZERO);
        assertThat(ledger.netEarning(loan)).isEqualTo(Credits.of("1200.00"));
    }

    // ---------------------------------------------------------------- helpers

    private long originalEarning() {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE loan_id = ? AND corrects_id IS NULL", Long.class,
                loan.value());
    }

    private RequestLineId line() {
        return new RequestLineId(testData().requestLine(lending));
    }

    private Map<String, Object> account() {
        return jdbc.queryForMap("SELECT granted, earned, held FROM account WHERE team_id = ?", lending);
    }

    private Map<String, Object> foldOfTransactions() {
        return jdbc.queryForMap("""
                SELECT coalesce(sum(CASE kind WHEN 'CHARGE' THEN -granted_part ELSE granted_part END), 0)::numeric(12,2) AS granted,
                       coalesce(sum(CASE kind WHEN 'CHARGE' THEN -earned_part ELSE earned_part END), 0)::numeric(12,2) AS earned,
                       (SELECT coalesce(sum(amount), 0) FROM held_credit WHERE team_id = ?)::numeric(12,2) AS held
                FROM transaction WHERE team_id = ?""", lending, lending);
    }

    private static void assertRefused(ThrowingCallable call, String shortfall) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RuleViolation.class, e -> {
            assertThat(e.code()).isEqualTo("INSUFFICIENT_BALANCE");
            assertThat(e.properties()).containsEntry("shortfall", Credits.of(shortfall));
        });
    }
}
