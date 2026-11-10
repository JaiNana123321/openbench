package edu.cwru.openbench.ledger;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link LedgerService#refund}: the Charge's proportions, the cap at the Charge, and the fold. */
@DisplayName("Release.Refund: LedgerService.refund")
class LedgerRefundTest extends PostgresTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Clock clock;

    private long team;
    private TeamId teamId;
    private long period;

    @BeforeEach
    void createATeamInTheCurrentPeriod() {
        period = testData().periodContaining(LocalDate.now(clock));
        team = testData().team(unique("Research"));
        teamId = new TeamId(team);
    }

    @Test
    @DisplayName("The design's spend-order example: a Refund of 20 on a Charge of 30 granted and 10 earned returns 15 and 5")
    void splitsInTheChargesProportions() {
        grant("30.00");
        testData().postEarning(team, "50.00");
        RequestLineId line = chargedLine("40.00");

        ledger.refund(teamId, line, Credits.of("20.00"));

        assertThat(refundsOf(line)).containsExactly(Map.of(
                "amount", new BigDecimal("20.00"), "granted_part", new BigDecimal("15.00"),
                "earned_part", new BigDecimal("5.00")));
        assertThat(ledger.account(teamId)).isEqualTo(new AccountView(teamId,
                Credits.of("15.00"), Credits.of("45.00"), Credits.ZERO, Credits.of("60.00"), Credits.of("60.00")));
    }

    @Test
    @DisplayName("rel-bucket: a Refund of 360.00 on a Charge of 120.00 granted and 360.00 earned returns 90.00 and 270.00")
    @Verifies(value = "rel-bucket", partial = true)
    void splitsTheBucketCase() {
        grant("600.00");
        testData().postEarning(team, "800.00");
        chargedLine("480.00");
        RequestLineId second = chargedLine("480.00");

        ledger.refund(teamId, second, Credits.of("360.00"));

        assertThat(refundsOf(second)).containsExactly(Map.of(
                "amount", new BigDecimal("360.00"), "granted_part", new BigDecimal("90.00"),
                "earned_part", new BigDecimal("270.00")));
        assertThat(ledger.account(teamId).granted()).isEqualTo(Credits.of("90.00"));
        assertThat(ledger.account(teamId).earned()).isEqualTo(Credits.of("710.00"));
    }

    @Test
    @DisplayName("Several Refunds on one line keep the Charge's proportions together, rounding included")
    void keepsTheProportionsOverSeveralRefunds() {
        grant("10.00");
        testData().postEarning(team, "100.00");
        // Charge 30.00: 10.00 granted, 20.00 earned, so one third returns to granted.
        RequestLineId line = chargedLine("30.00");

        ledger.refund(teamId, line, Credits.of("0.01"));
        ledger.refund(teamId, line, Credits.of("0.01"));
        ledger.refund(teamId, line, Credits.of("0.01"));

        assertThat(jdbc.queryForMap("""
                SELECT sum(granted_part) AS granted, sum(earned_part) AS earned FROM transaction
                WHERE kind = 'REFUND' AND line_id = ?""", line.value()))
                .isEqualTo(Map.of("granted", new BigDecimal("0.01"), "earned", new BigDecimal("0.02")));
        assertAccountFolds();
    }

    @Test
    @DisplayName("REFUND_EXCEEDS_CHARGE: a line's Refunds stay within its Charge")
    void refusesARefundBeyondTheCharge() {
        grant("100.00");
        RequestLineId line = chargedLine("30.00");
        ledger.refund(teamId, line, Credits.of("22.50"));

        assertThatThrownBy(() -> ledger.refund(teamId, line, Credits.of("7.51")))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_CHARGE"));

        ledger.refund(teamId, line, Credits.of("7.50"));
        assertThat(refundsOf(line)).hasSize(2);
        assertAccountFolds();
    }

    @Test
    @DisplayName("REFUND_EXCEEDS_CHARGE: a line without a Charge gets no Refund")
    void refusesARefundWithoutACharge() {
        grant("100.00");
        RequestLineId line = new RequestLineId(testData().requestLine(team));

        assertThatThrownBy(() -> ledger.refund(teamId, line, Credits.of("1.00")))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_CHARGE"));
    }

    @Test
    @DisplayName("A Refund of 0.00 posts nothing; a negative one is a bug")
    void postsNothingForZero() {
        grant("100.00");
        RequestLineId line = chargedLine("30.00");

        ledger.refund(teamId, line, Credits.ZERO);

        assertThat(refundsOf(line)).isEmpty();
        assertThatThrownBy(() -> ledger.refund(teamId, line, Credits.of("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Statement: a Refund shows against its line and in the totals")
    void showsOnTheStatement() throws Exception {
        grant("100.00");
        RequestLineId line = chargedLine("30.00");
        String manager = unique("manager");
        testData().member(team, manager, Role.MANAGER);

        ledger.refund(teamId, line, Credits.of("22.50"));

        mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(signedInAs(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.refunds").value(22.50))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'REFUND')].line").value((int) line.value()))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'REFUND')].amount").value(22.50));
    }

    private RequestLineId chargedLine(String amount) {
        RequestLineId line = new RequestLineId(testData().requestLine(team));
        ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(teamId, line, Credits.of(amount))));
        return line;
    }

    private List<Map<String, Object>> refundsOf(RequestLineId line) {
        return jdbc.queryForList("SELECT amount, granted_part, earned_part FROM transaction WHERE kind = 'REFUND' AND line_id = ? ORDER BY id",
                line.value());
    }

    private void grant(String amount) {
        ledger.postGrant(teamId, new PeriodId(period), Credits.of(amount), null, null);
    }

    private void assertAccountFolds() {
        assertThat(jdbc.queryForMap("SELECT granted, earned FROM account WHERE team_id = ?", team)).isEqualTo(jdbc.queryForMap("""
                SELECT coalesce(sum(CASE kind WHEN 'CHARGE' THEN -granted_part ELSE granted_part END), 0)::numeric(12,2) AS granted,
                       coalesce(sum(CASE kind WHEN 'CHARGE' THEN -earned_part ELSE earned_part END), 0)::numeric(12,2) AS earned
                FROM transaction WHERE team_id = ?""", team));
    }
}
