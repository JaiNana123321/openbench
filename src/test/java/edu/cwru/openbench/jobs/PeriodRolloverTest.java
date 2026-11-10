package edu.cwru.openbench.jobs;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.TestData.PeriodBoundary;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.ledger.AccountView;
import edu.cwru.openbench.ledger.ChargeSpec;
import edu.cwru.openbench.ledger.LedgerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Period rollover (design Scenario S7, SRS Ledger.Grant.Period) through {@link PeriodRolloverJob},
 * called directly with the movable Clock. Each test crosses a boundary of its own: two far-future
 * Periods from {@code TestData.periodBoundary()}, the ending one a single day and the starting one
 * two days. The rollover closes every Team in the shared database, so assertions name this test's
 * Teams only.
 */
class PeriodRolloverTest extends PostgresTest {

    @Autowired
    private PeriodRolloverJob job;

    @Autowired
    private LedgerService ledger;

    private PeriodBoundary boundary;
    private long team;
    private String manager;
    private long managerId;

    @BeforeEach
    void createATeamAndABoundary() {
        boundary = testData().periodBoundary();
        team = testData().team(unique("Operations"));
        manager = unique("oscar");
        managerId = testData().member(team, manager, Role.MANAGER);
        movableClock.set(at(boundary.endingOn(), 12, 0));
    }

    @Test
    @DisplayName("led-period-boundary: granted credits expire, earned credits stay, and the new Grant posts")
    @Verifies("led-period-boundary")
    void rollsOverTheTestCase() throws Exception {
        // Preconditions. Granted 970.00 after a Charge of 30.00. The earned 800.00 would come from
        // Dave Kim's Loan (#6) and Erin's Grant for the next Period from her settings screen (#13);
        // until those exist, TestData.postEarning and TestData.grantPlan write them as the Ledger and
        // Settings would.
        ledger.postGrant(teamId(), new PeriodId(boundary.ending()), Credits.of("1000.00"), null, null);
        charge("30.00");
        testData().postEarning(team, "800.00");
        testData().grantPlan(team, boundary.starting(), "1000.00");
        assertThat(ledger.account(teamId())).isEqualTo(account("970.00", "800.00", "0.00"));

        // 1. Let the current Period end: the job's run at 00:05 on the first day rolls over.
        runAt(boundary.startsOn(), 0, 5);

        // 2. The Statement of the ended Period: expired 970.00; closing granted 0.00, earned 800.00.
        mvc.perform(get("/api/ledger/statements/{team}/{period}", team, boundary.ending()).with(signedInAs(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.expired").value(970.00))
                .andExpect(jsonPath("$.closing.granted").value(0.00))
                .andExpect(jsonPath("$.closing.earned").value(800.00));

        // 3. The Statement of the new Period: opening granted 0.00, earned 800.00; a Grant of 1000.00
        // posted by the job (no actor); Balance 1800.00.
        mvc.perform(get("/api/ledger/statements/{team}/{period}", team, boundary.starting()).with(signedInAs(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opening.granted").value(0.00))
                .andExpect(jsonPath("$.opening.earned").value(800.00))
                .andExpect(jsonPath("$.totals.grants").value(1000.00))
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].kind").value("GRANT"))
                .andExpect(jsonPath("$.transactions[0].amount").value(1000.00))
                .andExpect(jsonPath("$.transactions[0].actor").isEmpty())
                .andExpect(jsonPath("$.account.balance").value(1800.00));

        // Pass criteria: earned credits survived the boundary and only granted credits expired.
        assertThat(ledger.account(teamId())).isEqualTo(account("1000.00", "800.00", "0.00"));
        assertThat(periodClose()).containsEntry("expired", new BigDecimal("970.00"))
                .containsEntry("closing_granted", new BigDecimal("0.00"))
                .containsEntry("closing_earned", new BigDecimal("800.00"));
        assertThat(noticesTo(managerId)).singleElement().satisfies(notice -> {
            assertThat(notice).containsEntry("kind", "PERIOD_ROLLOVER").containsEntry("link", "/statement");
            assertThat((String) notice.get("text")).contains("970.00 granted credits expired")
                    .contains("A Grant of 1000.00 credits is posted");
        });
    }

    @Test
    @DisplayName("Rollover is idempotent: a second run for the same boundary changes nothing")
    void changesNothingTheSecondTime() {
        ledger.postGrant(teamId(), new PeriodId(boundary.ending()), Credits.of("500.00"), null, null);
        testData().grantPlan(team, boundary.starting(), "600.00");
        runAt(boundary.startsOn(), 0, 5);
        AccountView afterFirst = ledger.account(teamId());
        int transactions = count("SELECT count(*) FROM transaction WHERE team_id = ?");
        int notices = noticesTo(managerId).size();

        runAt(boundary.startsOn(), 0, 10);
        job.catchUp();
        assertThat(ledger.rollover(new PeriodId(boundary.ending()), new PeriodId(boundary.starting()))).isZero();

        assertThat(ledger.account(teamId())).isEqualTo(afterFirst).isEqualTo(account("600.00", "0.00", "0.00"));
        assertThat(count("SELECT count(*) FROM transaction WHERE team_id = ?")).isEqualTo(transactions);
        assertThat(count("SELECT count(*) FROM period_close WHERE team_id = ?")).isEqualTo(1);
        assertThat(noticesTo(managerId)).hasSize(notices);
    }

    @Test
    @DisplayName("Earned and held credits survive the boundary; only granted credits expire")
    void keepsEarnedAndHeldCredits() {
        ledger.postGrant(teamId(), new PeriodId(boundary.ending()), Credits.of("400.00"), null, null);
        testData().postEarning(team, "250.00");
        ledger.holdCredits(teamId(), new RequestLineId(testData().requestLine(team)), Credits.of("100.00"));

        runAt(boundary.startsOn(), 0, 5);

        assertThat(ledger.account(teamId())).isEqualTo(account("0.00", "250.00", "100.00"));
        assertThat(periodClose()).containsEntry("expired", new BigDecimal("400.00"))
                .containsEntry("closing_earned", new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("No Grant posts when the planned amount is 0")
    void postsNoGrantOfZero() {
        ledger.postGrant(teamId(), new PeriodId(boundary.ending()), Credits.of("300.00"), null, null);
        testData().grantPlan(team, boundary.starting(), "0.00");

        runAt(boundary.startsOn(), 0, 5);

        assertThat(count("SELECT count(*) FROM transaction WHERE team_id = ? AND period_id = " + boundary.starting()))
                .isZero();
        assertThat(ledger.account(teamId())).isEqualTo(account("0.00", "0.00", "0.00"));
        assertThat(noticesTo(managerId)).singleElement()
                .satisfies(notice -> assertThat((String) notice.get("text")).contains("300.00 granted credits expired")
                        .doesNotContain("A Grant of"));
    }

    @Test
    @DisplayName("A Team with nothing to expire and no Grant is closed without a notice")
    void closesAQuietTeamWithoutANotice() {
        ledger.postGrant(teamId(), new PeriodId(boundary.ending()), Credits.of("30.00"), null, null);
        charge("30.00");
        long noAccount = testData().team(unique("No account"));

        runAt(boundary.startsOn(), 0, 5);

        assertThat(periodClose()).containsEntry("expired", new BigDecimal("0.00"));
        assertThat(noticesTo(managerId)).isEmpty();
        // A Team without an account row and without a plan has nothing to close.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM period_close WHERE team_id = ?", Integer.class, noAccount))
                .isZero();
    }

    @Test
    @DisplayName("The planned Grant is the Team's most recent earlier plan when the new Period has none")
    void postsTheMostRecentEarlierPlan() {
        testData().grantPlan(team, boundary.ending(), "750.00");

        runAt(boundary.startsOn(), 0, 5);

        assertThat(ledger.account(teamId())).isEqualTo(account("750.00", "0.00", "0.00"));
    }

    @Test
    @DisplayName("catchUp covers one missed boundary; the daily run acts only on a Period's first day")
    void catchesUpOnAMissedBoundary() {
        ledger.postGrant(teamId(), new PeriodId(boundary.ending()), Credits.of("200.00"), null, null);
        testData().grantPlan(team, boundary.starting(), "900.00");

        // The server was down at 00:05 on the first day; on the second day the daily run does nothing.
        runAt(boundary.startsOn().plusDays(1), 0, 5);
        assertThat(ledger.account(teamId())).isEqualTo(account("200.00", "0.00", "0.00"));
        assertThat(count("SELECT count(*) FROM period_close WHERE team_id = ?")).isZero();

        // Start-up: the catch-up rolls over into the current Period.
        job.catchUp();
        assertThat(ledger.account(teamId())).isEqualTo(account("900.00", "0.00", "0.00"));
        assertThat(periodClose()).containsEntry("expired", new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("A Grant posted into the new Period before the rollover ran does not expire, and the plan posts once")
    void keepsCreditsGrantedForTheNewPeriod() {
        ledger.postGrant(teamId(), new PeriodId(boundary.ending()), Credits.of("100.00"), null, null);
        testData().grantPlan(team, boundary.starting(), "500.00");
        movableClock.set(at(boundary.startsOn(), 0, 1));
        ledger.postGrant(teamId(), new PeriodId(boundary.starting()), Credits.of("40.00"), "Workshop", null);
        ledger.postGrant(teamId(), new PeriodId(boundary.starting()), Credits.of("500.00"), null, null);

        runAt(boundary.startsOn(), 0, 5);

        // 100.00 expired; the mid-Period 40.00 and the already posted planned 500.00 stay; no second plan.
        assertThat(periodClose()).containsEntry("expired", new BigDecimal("100.00"))
                .containsEntry("closing_granted", new BigDecimal("0.00"));
        assertThat(ledger.account(teamId())).isEqualTo(account("540.00", "0.00", "0.00"));
        assertThat(count("SELECT count(*) FROM transaction WHERE team_id = ? AND period_id = " + boundary.starting()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("A Team with a planned Grant but no account row yet gets the row and the Grant")
    void createsTheAccountOfAPlannedTeam() {
        long newTeam = testData().team(unique("New team"));
        PeriodBoundary next = testData().periodBoundary();
        testData().grantPlan(newTeam, next.starting(), "250.00");

        runAt(next.startsOn(), 0, 5);

        assertThat(ledger.account(new TeamId(newTeam))).isEqualTo(
                new AccountView(new TeamId(newTeam), Credits.of("250.00"), Credits.ZERO, Credits.ZERO,
                        Credits.of("250.00"), Credits.of("250.00")));
    }

    @Test
    @DisplayName("The rollover refuses Periods that are not adjacent, and a boundary that has not come yet")
    void refusesAWrongBoundary() {
        PeriodId ending = new PeriodId(boundary.ending());
        PeriodId starting = new PeriodId(boundary.starting());

        assertThatThrownBy(() -> ledger.rollover(starting, ending)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not the Period right before");
        assertThatThrownBy(() -> ledger.rollover(ending, starting)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rolled over into before then");
        assertThat(count("SELECT count(*) FROM period_close WHERE team_id = ?")).isZero();
    }

    @Test
    @DisplayName("A job without a current Period logs the failure and returns")
    void logsAFailedRun() {
        movableClock.set(Instant.parse("3000-01-01T05:00:00Z"));

        job.run();
        job.catchUp();

        assertThat(count("SELECT count(*) FROM period_close WHERE team_id = ?")).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private TeamId teamId() {
        return new TeamId(team);
    }

    private void runAt(LocalDate day, int hour, int minute) {
        movableClock.set(at(day, hour, minute));
        job.run();
    }

    private Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(LocalTime.of(hour, minute)).atZone(movableClock.getZone()).toInstant();
    }

    private void charge(String amount) {
        long line = testData().requestLine(team);
        long request = jdbc.queryForObject("SELECT request_id FROM request_line WHERE id = ?", Long.class, line);
        ledger.commitCharges(new RequestId(request),
                List.of(new ChargeSpec(teamId(), new RequestLineId(line), Credits.of(amount))));
    }

    private AccountView account(String granted, String earned, String held) {
        Credits g = Credits.of(granted);
        Credits e = Credits.of(earned);
        Credits h = Credits.of(held);
        return new AccountView(teamId(), g, e, h, g.plus(e), g.plus(e).minus(h));
    }

    private Map<String, Object> periodClose() {
        return jdbc.queryForMap("SELECT expired, closing_granted, closing_earned FROM period_close WHERE team_id = ? AND period_id = ?",
                team, boundary.ending());
    }

    private List<Map<String, Object>> noticesTo(long member) {
        return jdbc.queryForList("SELECT kind, text, link FROM notification WHERE member_id = ? ORDER BY id", member);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, team);
    }
}
