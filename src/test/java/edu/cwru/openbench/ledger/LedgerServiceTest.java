package edu.cwru.openbench.ledger;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.transaction.support.TransactionTemplate;

/** The Account, held credits, Charges, and Grants, against the real schema. */
class LedgerServiceTest extends PostgresTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Clock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserDetailsService userDetailsService;

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
    @DisplayName("Ledger.Account: Balance is granted plus earned, Available Balance is Balance minus held")
    void computesTheAccountFigures() {
        grant("600.00");
        earn("50.00");
        ledger.holdCredits(teamId, line(), Credits.of("100.00"));

        assertThat(ledger.account(teamId)).isEqualTo(new AccountView(teamId,
                Credits.of("600.00"), Credits.of("50.00"), Credits.of("100.00"), Credits.of("650.00"), Credits.of("550.00")));
    }

    @Test
    void showsZeroForATeamWithoutTransactions() {
        assertThat(ledger.account(teamId)).isEqualTo(
                new AccountView(teamId, Credits.ZERO, Credits.ZERO, Credits.ZERO, Credits.ZERO, Credits.ZERO));
    }

    @Test
    void acceptsAnAffordableSumUpToTheAvailableBalance() {
        grant("600.00");
        ledger.holdCredits(teamId, line(), Credits.of("100.00"));

        ledger.requireAffordable(teamId, Credits.of("500.00"));
    }

    @Test
    @DisplayName("INSUFFICIENT_BALANCE: a sum above the Available Balance is refused with the shortfall")
    void refusesASumAboveTheAvailableBalanceWithTheShortfall() {
        grant("600.00");
        ledger.holdCredits(teamId, line(), Credits.of("100.00"));

        assertRefused(() -> ledger.requireAffordable(teamId, Credits.of("550.00")), "50.00");
        assertRefused(() -> ledger.requireAffordable(teamId, Credits.of("500.01")), "0.01");
    }

    @Test
    void holdsThenReleasesALinesCredits() {
        grant("600.00");
        RequestLineId line = line();

        ledger.holdCredits(teamId, line, Credits.of("120.00"));
        assertThat(ledger.account(teamId).held()).isEqualTo(Credits.of("120.00"));
        assertThat(heldOf(line)).isEqualTo(new BigDecimal("120.00"));

        ledger.releaseCredits(teamId, line);
        assertThat(ledger.account(teamId).held()).isEqualTo(Credits.ZERO);
        assertThat(ledger.account(teamId).available()).isEqualTo(Credits.of("600.00"));
        assertThat(heldOf(line)).isNull();

        // Releasing a line that is not on Hold changes nothing.
        ledger.releaseCredits(teamId, line);
        assertThat(ledger.account(teamId).held()).isEqualTo(Credits.ZERO);
    }

    @Test
    @DisplayName("INSUFFICIENT_BALANCE: a Hold above the Available Balance is refused and holds nothing")
    void refusesAHoldAboveTheAvailableBalance() {
        grant("600.00");
        ledger.holdCredits(teamId, line(), Credits.of("500.00"));
        RequestLineId line = line();

        assertRefused(() -> ledger.holdCredits(teamId, line, Credits.of("120.00")), "20.00");
        assertThat(ledger.account(teamId).held()).isEqualTo(Credits.of("500.00"));
        assertThat(heldOf(line)).isNull();
    }

    @Test
    void refusesToHoldALineTwice() {
        grant("600.00");
        RequestLineId line = line();
        ledger.holdCredits(teamId, line, Credits.of("10.00"));

        assertThatThrownBy(() -> ledger.holdCredits(teamId, line, Credits.of("10.00"))).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.account(teamId).held()).isEqualTo(Credits.of("10.00"));
    }

    @Test
    @DisplayName("Ledger.SpendOrder: a Charge draws granted credits first, then earned, and records the split")
    void commitsAHeldLineGrantedCreditsFirst() {
        // The design's worked example: granted 30 and earned 50 meet a Charge of 40.
        grant("30.00");
        earn("50.00");
        RequestLineId line = line();
        ledger.holdCredits(teamId, line, Credits.of("40.00"));

        ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(teamId, line, Credits.of("40.00"))));

        assertThat(ledger.account(teamId)).isEqualTo(new AccountView(teamId,
                Credits.ZERO, Credits.of("40.00"), Credits.ZERO, Credits.of("40.00"), Credits.of("40.00")));
        assertThat(heldOf(line)).isNull();
        assertThat(jdbc.queryForMap("SELECT amount, granted_part, earned_part, period_id FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                line.value())).isEqualTo(Map.of(
                        "amount", new BigDecimal("40.00"), "granted_part", new BigDecimal("30.00"),
                        "earned_part", new BigDecimal("10.00"), "period_id", period));
    }

    @Test
    void commitsALineThatWasNeverHeld() {
        grant("100.00");
        RequestLineId line = line();

        ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(teamId, line, Credits.of("60.00"))));

        assertThat(ledger.account(teamId).granted()).isEqualTo(Credits.of("40.00"));
        assertThat(ledger.account(teamId).held()).isEqualTo(Credits.ZERO);
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?", BigDecimal.class,
                line.value())).isEqualTo(new BigDecimal("60.00"));
    }

    @Test
    void commitsSeveralLinesOfARequestTogether() {
        grant("100.00");
        earn("100.00");
        RequestLineId held = line();
        RequestLineId waiting = line();
        ledger.holdCredits(teamId, held, Credits.of("80.00"));

        ledger.commitCharges(new RequestId(1), List.of(
                new ChargeSpec(teamId, held, Credits.of("80.00")),
                new ChargeSpec(teamId, waiting, Credits.of("70.00"))));

        assertThat(ledger.account(teamId)).isEqualTo(new AccountView(teamId,
                Credits.ZERO, Credits.of("50.00"), Credits.ZERO, Credits.of("50.00"), Credits.of("50.00")));
        assertThat(jdbc.queryForList("SELECT granted_part || '/' || earned_part FROM transaction WHERE kind = 'CHARGE' AND team_id = ? ORDER BY id",
                String.class, team)).containsExactly("80.00/0.00", "20.00/50.00");
    }

    @Test
    @DisplayName("Ledger.NoNegative: a Charge never takes the Balance below zero, nor spends another line's held credits")
    void refusesAChargeTheAvailableBalanceCannotCover() {
        grant("100.00");
        RequestLineId held = line();
        ledger.holdCredits(teamId, held, Credits.of("80.00"));
        RequestLineId other = line();

        assertRefused(() -> ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(teamId, other, Credits.of("30.00")))), "10.00");
        assertRefused(() -> ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(teamId, held, Credits.of("120.00")))), "20.00");

        // Nothing was posted or released by the refused calls.
        assertThat(ledger.account(teamId)).isEqualTo(new AccountView(teamId,
                Credits.of("100.00"), Credits.ZERO, Credits.of("80.00"), Credits.of("100.00"), Credits.of("20.00")));
        assertThat(heldOf(held)).isEqualTo(new BigDecimal("80.00"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE kind = 'CHARGE' AND team_id = ?", Long.class, team)).isZero();
    }

    @Test
    void refusesEveryChargeOfARequestWhenTheirSumDoesNotFit() {
        grant("100.00");

        assertRefused(() -> ledger.commitCharges(new RequestId(1), List.of(
                new ChargeSpec(teamId, line(), Credits.of("60.00")),
                new ChargeSpec(teamId, line(), Credits.of("50.00")))), "10.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE kind = 'CHARGE' AND team_id = ?", Long.class, team)).isZero();
        assertThat(ledger.account(teamId).balance()).isEqualTo(Credits.of("100.00"));
    }

    @Test
    void namesTheSignedInMemberAsTheChargesActorAndTheSystemOtherwise() {
        grant("100.00");
        String username = unique("bob");
        long bob = testData().member(team, username, Role.MANAGER);
        RequestLineId byBob = line();
        RequestLineId byJob = line();

        UserDetails signedIn = userDetailsService.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(signedIn, null, signedIn.getAuthorities()));
        try {
            ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(teamId, byBob, Credits.of("10.00"))));
        } finally {
            SecurityContextHolder.clearContext();
        }
        ledger.commitCharges(new RequestId(2), List.of(new ChargeSpec(teamId, byJob, Credits.of("10.00"))));

        assertThat(jdbc.queryForObject("SELECT actor_id FROM transaction WHERE line_id = ?", Long.class, byBob.value())).isEqualTo(bob);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM transaction WHERE line_id = ?", Long.class, byJob.value())).isNull();
    }

    @Test
    void postsAGrantWithItsReasonAndActor() {
        long planner = testData().member(team, unique("erin"), Role.PLANNER);
        Actor actor = new Actor(new MemberId(planner), teamId, Set.of(Role.PLANNER));

        ledger.postGrant(teamId, new PeriodId(period), Credits.of("250.00"), "Settles an escalation", actor);

        assertThat(ledger.account(teamId).granted()).isEqualTo(Credits.of("250.00"));
        assertThat(jdbc.queryForMap("SELECT kind, amount, granted_part, earned_part, reason, actor_id FROM transaction WHERE team_id = ?", team))
                .isEqualTo(Map.of("kind", "GRANT", "amount", new BigDecimal("250.00"), "granted_part", new BigDecimal("250.00"),
                        "earned_part", new BigDecimal("0.00"), "reason", "Settles an escalation", "actor_id", planner));
    }

    @Test
    @DisplayName("INVALID_AMOUNT: a Grant must be positive")
    void refusesAGrantThatIsNotPositive() {
        assertThatThrownBy(() -> ledger.postGrant(teamId, new PeriodId(period), Credits.ZERO, null, null))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("INVALID_AMOUNT"));
        assertThatThrownBy(() -> ledger.postGrant(teamId, new PeriodId(period), Credits.of("-5.00"), null, null))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("INVALID_AMOUNT"));
        assertThat(ledger.account(teamId).granted()).isEqualTo(Credits.ZERO);
    }

    @Test
    void refusesAGrantIntoAnUnknownPeriod() {
        assertThatThrownBy(() -> ledger.postGrant(teamId, new PeriodId(Long.MAX_VALUE), Credits.of("1.00"), null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("The account row equals the fold of the Team's Transactions and Period closes")
    void keepsTheAccountRowEqualToTheFoldOfTheTransactions() {
        grant("500.00");
        earn("75.00");
        RequestLineId charged = line();
        ledger.holdCredits(teamId, charged, Credits.of("120.00"));
        ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(teamId, charged, Credits.of("120.00"))));
        RequestLineId released = line();
        ledger.holdCredits(teamId, released, Credits.of("40.00"));
        ledger.releaseCredits(teamId, released);
        ledger.holdCredits(teamId, line(), Credits.of("33.33"));
        ledger.commitCharges(new RequestId(2), List.of(new ChargeSpec(teamId, line(), Credits.of("400.00"))));

        Map<String, Object> fold = jdbc.queryForMap("""
                SELECT coalesce(sum(CASE kind WHEN 'CHARGE' THEN -granted_part ELSE granted_part END), 0)
                         - (SELECT coalesce(sum(expired), 0) FROM period_close WHERE team_id = :team) AS granted,
                       coalesce(sum(CASE kind WHEN 'CHARGE' THEN -earned_part ELSE earned_part END), 0) AS earned,
                       (SELECT coalesce(sum(amount), 0) FROM held_credit WHERE team_id = :team) AS held
                FROM transaction WHERE team_id = :team""".replace(":team", Long.toString(team)));
        Map<String, Object> row = jdbc.queryForMap("SELECT granted, earned, held FROM account WHERE team_id = ?", team);

        assertThat(row).isEqualTo(fold);
        assertThat(row).isEqualTo(Map.of("granted", new BigDecimal("0.00"), "earned", new BigDecimal("55.00"), "held", new BigDecimal("33.33")));
    }

    @Test
    @DisplayName("Two concurrent Holds on one Account cannot together exceed the Available Balance")
    void serializesConcurrentHoldsOnTheAccountRow() throws Exception {
        grant("100.00");
        RequestLineId first = line();
        RequestLineId second = line();
        CountDownLatch firstHolds = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // The first Hold keeps its transaction (and the account lock) open while the second starts.
            Future<?> firstHold = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                ledger.holdCredits(teamId, first, Credits.of("60.00"));
                firstHolds.countDown();
                pause();
            }));
            assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> secondHold = threads.submit(() -> ledger.holdCredits(teamId, second, Credits.of("60.00")));

            firstHold.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> secondHold.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_BALANCE"));
        } finally {
            threads.shutdownNow();
        }
        assertThat(ledger.account(teamId).held()).isEqualTo(Credits.of("60.00"));
        assertThat(heldOf(second)).isNull();
    }

    private void grant(String amount) {
        ledger.postGrant(teamId, new PeriodId(period), Credits.of(amount), null, null);
    }

    /** An Earning, written as the schema owner because postEarning is not in Demo 1. */
    private void earn(String amount) {
        BigDecimal earned = new BigDecimal(amount);
        jdbc.update("""
                INSERT INTO transaction (team_id, kind, amount, granted_part, earned_part, period_id, posted_at)
                VALUES (?, 'EARNING', ?, 0, ?, ?, now())""", team, earned, earned, period);
        jdbc.update("""
                INSERT INTO account (team_id, earned) VALUES (?, ?)
                ON CONFLICT (team_id) DO UPDATE SET earned = account.earned + EXCLUDED.earned""", team, earned);
    }

    private RequestLineId line() {
        return new RequestLineId(testData().requestLine(team));
    }

    private BigDecimal heldOf(RequestLineId line) {
        return jdbc.query("SELECT amount FROM held_credit WHERE line_id = ?",
                rows -> rows.next() ? rows.getBigDecimal(1) : null, line.value());
    }

    private static void assertRefused(ThrowingCallable call, String shortfall) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RuleViolation.class, e -> {
            assertThat(e.code()).isEqualTo("INSUFFICIENT_BALANCE");
            assertThat(e.properties()).containsEntry("shortfall", Credits.of(shortfall));
        });
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
