package edu.cwru.openbench.ledger;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** The Account and Statement endpoints with their scoping, and the Planner's mid-Period Grant. */
class LedgerControllerTest extends PostgresTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Clock clock;

    private long period;
    private long earlierPeriod;
    private long laterPeriod;
    private long research;
    private String researchName;
    private String bob;
    private String rita;
    private long platform;
    private String alice;
    private String erin;
    private long erinId;

    @BeforeEach
    void createTwoTeamsAndAPlanner() {
        period = testData().periodContaining(LocalDate.now(clock));
        earlierPeriod = testData().periodContaining(LocalDate.of(1990, 1, 1));
        laterPeriod = testData().period();
        researchName = unique("Research");
        research = testData().team(researchName);
        bob = unique("bob");
        testData().member(research, bob, Role.MANAGER);
        rita = unique("rita");
        testData().member(research, rita);
        platform = testData().team(unique("Platform"));
        alice = unique("alice");
        testData().member(platform, alice, Role.MANAGER);
        long operations = testData().team(unique("Operations"));
        erin = unique("erin");
        erinId = testData().member(operations, erin, Role.PLANNER);
    }

    @Test
    void showsAManagerTheirOwnTeamsAccount() throws Exception {
        ledger.postGrant(new TeamId(research), new PeriodId(period), Credits.of("600.00"), null, null);
        ledger.holdCredits(new TeamId(research), line(research), Credits.of("30.00"));

        mvc.perform(get("/api/ledger/account").with(signedInAs(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team").value(research))
                .andExpect(jsonPath("$.granted").value(600.00))
                .andExpect(jsonPath("$.earned").value(0.00))
                .andExpect(jsonPath("$.held").value(30.00))
                .andExpect(jsonPath("$.balance").value(600.00))
                .andExpect(jsonPath("$.available").value(570.00));
    }

    @Test
    void showsThePlannerAnyTeamsAccount() throws Exception {
        ledger.postGrant(new TeamId(research), new PeriodId(period), Credits.of("600.00"), null, null);

        mvc.perform(get("/api/ledger/account").param("teamId", Long.toString(research)).with(signedInAs(erin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team").value(research))
                .andExpect(jsonPath("$.balance").value(600.00));
    }

    @Test
    @DisplayName("A Manager asking for another Team's Account gets 404 NOT_FOUND")
    void hidesAnotherTeamsAccountFromAManager() throws Exception {
        mvc.perform(get("/api/ledger/account").param("teamId", Long.toString(research)).with(signedInAs(alice)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("A Member who is neither Manager nor Planner gets 403 FORBIDDEN for the Account and the Statement")
    void refusesTheLedgerToAPlainMember() throws Exception {
        mvc.perform(get("/api/ledger/account").with(signedInAs(rita)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/ledger/statements/{team}/{period}", research, period).with(signedInAs(rita)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Ledger.Statement: opening, totals, closing, the Account, and every Transaction of the Period")
    void showsTheStatementOfAPeriod() throws Exception {
        TeamId team = new TeamId(research);
        // Before the Period: a Grant of 100 that expired at the earlier Period's close, and an Earning of 50.
        ledger.postGrant(team, new PeriodId(earlierPeriod), Credits.of("100.00"), null, null);
        jdbc.update("INSERT INTO period_close (team_id, period_id, expired, closing_granted, closing_earned, closed_at) VALUES (?, ?, 100.00, 0, 50.00, now())",
                research, earlierPeriod);
        jdbc.update("UPDATE account SET granted = granted - 100.00, earned = earned + 50.00 WHERE team_id = ?", research);
        jdbc.update("""
                INSERT INTO transaction (team_id, kind, amount, granted_part, earned_part, period_id, posted_at)
                VALUES (?, 'EARNING', 50.00, 0, 50.00, ?, '1990-01-01T12:00:00Z')""", research, earlierPeriod);
        // In the Period: a Grant by the Planner with a reason, and a Charge of 30.
        Actor planner = new Actor(new MemberId(erinId), new TeamId(research), Set.of(Role.PLANNER));
        ledger.postGrant(team, new PeriodId(period), Credits.of("600.00"), "October Grant", planner);
        RequestLineId line = line(research);
        ledger.holdCredits(team, line, Credits.of("30.00"));
        ledger.commitCharges(new RequestId(1), List.of(new ChargeSpec(team, line, Credits.of("30.00"))));
        // After the Period: not on this Statement.
        ledger.postGrant(team, new PeriodId(laterPeriod), Credits.of("9.00"), null, null);

        mvc.perform(get("/api/ledger/statements/{team}/{period}", research, period).with(signedInAs(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.name").value(researchName))
                .andExpect(jsonPath("$.period.id").value(period))
                .andExpect(jsonPath("$.opening.granted").value(0.00))
                .andExpect(jsonPath("$.opening.earned").value(50.00))
                .andExpect(jsonPath("$.totals.grants").value(600.00))
                .andExpect(jsonPath("$.totals.charges").value(30.00))
                .andExpect(jsonPath("$.totals.earnings").value(0.00))
                .andExpect(jsonPath("$.totals.refunds").value(0.00))
                .andExpect(jsonPath("$.totals.expired").value(0.00))
                .andExpect(jsonPath("$.closing.granted").value(570.00))
                .andExpect(jsonPath("$.closing.earned").value(50.00))
                .andExpect(jsonPath("$.account.balance").value(629.00))
                .andExpect(jsonPath("$.transactions.length()").value(2))
                .andExpect(jsonPath("$.transactions[0].kind").value("GRANT"))
                .andExpect(jsonPath("$.transactions[0].amount").value(600.00))
                .andExpect(jsonPath("$.transactions[0].reason").value("October Grant"))
                .andExpect(jsonPath("$.transactions[0].actorName").value(erin))
                .andExpect(jsonPath("$.transactions[0].line").isEmpty())
                .andExpect(jsonPath("$.transactions[1].kind").value("CHARGE"))
                .andExpect(jsonPath("$.transactions[1].amount").value(30.00))
                .andExpect(jsonPath("$.transactions[1].grantedPart").value(30.00))
                .andExpect(jsonPath("$.transactions[1].line").value(line.value()))
                .andExpect(jsonPath("$.transactions[1].actor").isEmpty())
                .andExpect(jsonPath("$.transactions[1].postedAt").isString());

        mvc.perform(get("/api/ledger/statements/{team}/{period}", research, earlierPeriod).with(signedInAs(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opening.granted").value(0.00))
                .andExpect(jsonPath("$.totals.grants").value(100.00))
                .andExpect(jsonPath("$.totals.earnings").value(50.00))
                .andExpect(jsonPath("$.totals.expired").value(100.00))
                .andExpect(jsonPath("$.closing.granted").value(0.00))
                .andExpect(jsonPath("$.closing.earned").value(50.00));
    }

    @Test
    void showsThePlannerAnyTeamsStatement() throws Exception {
        mvc.perform(get("/api/ledger/statements/{team}/{period}", research, period).with(signedInAs(erin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.id").value(research))
                .andExpect(jsonPath("$.transactions").isEmpty());
    }

    @Test
    @DisplayName("A Manager asking for another Team's Statement gets 404 NOT_FOUND")
    void hidesAnotherTeamsStatementFromAManager() throws Exception {
        mvc.perform(get("/api/ledger/statements/{team}/{period}", research, period).with(signedInAs(alice)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void answers404ForAnUnknownTeamOrPeriod() throws Exception {
        mvc.perform(get("/api/ledger/statements/{team}/{period}", Long.MAX_VALUE, period).with(signedInAs(erin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/ledger/statements/{team}/{period}", research, Long.MAX_VALUE).with(signedInAs(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void letsThePlannerPostAMidPeriodGrant() throws Exception {
        mvc.perform(post("/api/ledger/grants").with(signedInAs(erin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": %d, \"amount\": 200.00, \"reason\": \"Settles an escalation\"}".formatted(research)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team").value(research))
                .andExpect(jsonPath("$.granted").value(200.00));

        assertThat(jdbc.queryForObject(
                        "SELECT reason FROM transaction WHERE team_id = ? AND kind = 'GRANT' AND period_id = ?", String.class, research, period))
                .isEqualTo("Settles an escalation");
    }

    @Test
    @DisplayName("REASON_REQUIRED and INVALID_AMOUNT: a mid-Period Grant needs a reason and a positive amount")
    void refusesAMidPeriodGrantWithoutAReasonOrAPositiveAmount() throws Exception {
        mvc.perform(post("/api/ledger/grants").with(signedInAs(erin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": %d, \"amount\": 200.00, \"reason\": \" \"}".formatted(research)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        mvc.perform(post("/api/ledger/grants").with(signedInAs(erin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": %d, \"amount\": 0, \"reason\": \"Nothing\"}".formatted(research)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        assertThat(ledger.account(new TeamId(research)).granted()).isEqualTo(Credits.ZERO);
    }

    @Test
    void refusesAMidPeriodGrantToAManager() throws Exception {
        mvc.perform(post("/api/ledger/grants").with(signedInAs(bob)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": %d, \"amount\": 200.00, \"reason\": \"Myself\"}".formatted(research)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private RequestLineId line(long team) {
        return new RequestLineId(testData().requestLine(team));
    }
}
