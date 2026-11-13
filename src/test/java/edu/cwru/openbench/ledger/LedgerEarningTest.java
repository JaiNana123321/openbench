package edu.cwru.openbench.ledger;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.LoanId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** {@link LedgerService#postEarning}: into earned credits, naming the Loan, once per Loan. */
@DisplayName("Ledger.Earning.Post: LedgerService.postEarning")
class LedgerEarningTest extends PostgresTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Clock clock;

    private long lending;
    private long loan;
    private long line;

    @BeforeEach
    void createALoan() {
        testData().periodContaining(LocalDate.now(clock));
        lending = testData().team(unique("Operations"));
        long borrowing = testData().team(unique("Research"));
        long dave = testData().member(lending, unique("dave"));
        LocalDate monday = LocalDate.of(2190, 1, 2);
        loan = testData().loan(dave, lending, borrowing, monday, monday.plusDays(1));
        line = jdbc.queryForObject("SELECT line_id FROM loan WHERE id = ?", Long.class, loan);
    }

    @Test
    @DisplayName("An Earning goes into earned credits, names its Loan and line, and folds into the Account")
    void postsIntoEarnedCredits() {
        ledger.postEarning(earning("800.00"), null);

        assertThat(jdbc.queryForMap("""
                SELECT amount, granted_part, earned_part, loan_id, line_id, actor_id, reason FROM transaction
                WHERE kind = 'EARNING' AND team_id = ?""", lending))
                .containsEntry("amount", new BigDecimal("800.00"))
                .containsEntry("granted_part", new BigDecimal("0.00"))
                .containsEntry("earned_part", new BigDecimal("800.00"))
                .containsEntry("loan_id", loan)
                .containsEntry("line_id", line)
                .containsEntry("actor_id", null)
                .containsEntry("reason", "Dave Kim to Research, 2 person-days");
        assertThat(ledger.account(new TeamId(lending)).earned()).isEqualTo(Credits.of("800.00"));
        assertThat(jdbc.queryForMap("SELECT granted, earned FROM account WHERE team_id = ?", lending))
                .isEqualTo(Map.of("granted", new BigDecimal("0.00"), "earned", new BigDecimal("800.00")));
    }

    @Test
    @DisplayName("A second Earning for the same Loan is refused, and the Account is unchanged")
    void refusesASecondEarningForTheLoan() {
        ledger.postEarning(earning("800.00"), null);

        assertThatThrownBy(() -> ledger.postEarning(earning("800.00"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has its Earning");
        assertThat(ledger.account(new TeamId(lending)).earned()).isEqualTo(Credits.of("800.00"));
        // The database refuses it too, even past the service; a correction naming the Earning is allowed.
        long period = jdbc.queryForObject("SELECT period_id FROM transaction WHERE loan_id = ?", Long.class, loan);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transaction (team_id, kind, amount, granted_part, earned_part, period_id, loan_id, posted_at)
                VALUES (?, 'EARNING', 800, 0, 800, ?, ?, now())""", lending, period, loan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("transaction_one_earning_per_loan");
        long earningId = jdbc.queryForObject("SELECT id FROM transaction WHERE loan_id = ?", Long.class, loan);
        assertThat(jdbc.update("""
                INSERT INTO transaction (team_id, kind, amount, granted_part, earned_part, period_id, loan_id,
                                         corrects_id, posted_at)
                VALUES (?, 'EARNING', -400, 0, -400, ?, ?, ?, now())""", lending, period, loan, earningId)).isEqualTo(1);
    }

    @Test
    @DisplayName("transaction.loan_id references a Loan; a negative Earning is not a postEarning")
    void theLoanMustExist() {
        long period = jdbc.queryForObject("SELECT id FROM period WHERE starts_on <= current_date AND ends_on >= current_date",
                Long.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transaction (team_id, kind, amount, granted_part, earned_part, period_id, loan_id, posted_at)
                VALUES (?, 'EARNING', 800, 0, 800, ?, ?, now())""", lending, period, Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("transaction_loan_id_fkey");
        assertThatThrownBy(() -> earning("-1.00")).isInstanceOf(IllegalArgumentException.class);
    }

    private EarningSpec earning(String amount) {
        return new EarningSpec(new TeamId(lending), new LoanId(loan), new RequestLineId(line), Credits.of(amount),
                "Dave Kim to Research, 2 person-days");
    }
}
