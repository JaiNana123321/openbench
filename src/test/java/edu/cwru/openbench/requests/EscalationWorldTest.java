package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import edu.cwru.openbench.common.RequestId;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base of the refusal and escalation tests (issue #7): the Matched data of the test world with the
 * Capacity Planner Erin Walsh, and helpers for the Objection Window's lapse, the Escalations list,
 * and the rows a Request holds. Other tests' Capacity Planners share the database, so every list a
 * Planner reads is checked for this test's Requests only.
 */
abstract class EscalationWorldTest extends MatchedWorldTest {

    @Autowired
    protected RequestLifecycleService lifecycle;

    protected long erinId;

    @BeforeEach
    void nameTheCapacityPlanner() {
        erinId = jdbc.queryForObject("SELECT id FROM member WHERE username = ?", Long.class, erin);
        jdbc.update("UPDATE member SET name = 'Erin Walsh' WHERE id = ?", erinId);
    }

    /** Moves the Clock a minute past the Request's Objection Window and runs its lapse, as DueWorkJob would. */
    protected void lapse(long request) {
        movableClock.set(windowEndOf(request).plusSeconds(60));
        assertThat(lifecycle.lapsedWindows(clock.instant())).contains(new RequestId(request));
        lifecycle.onWindowLapse(new RequestId(request));
    }

    protected Instant windowEndOf(long request) {
        return jdbc.queryForObject("SELECT window_ends_at FROM request WHERE id = ?", Timestamp.class, request)
                .toInstant();
    }

    protected ResultActions escalations(String caller) throws Exception {
        return mvc.perform(get("/api/escalations").with(as(caller)));
    }

    protected ResultActions statement(String manager, long team) throws Exception {
        return mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(as(manager)));
    }

    /** The approval of this Member as a Candidate of the line. */
    protected long approvalOf(long line, long member) {
        return jdbc.queryForObject("""
                SELECT a.id FROM approval a JOIN candidate c ON c.id = a.candidate_id
                WHERE c.line_id = ? AND c.member_id = ?""", Long.class, line, member);
    }

    /** The Request's HELD or ALLOCATED Exclusive and Metered rows. */
    protected int liveRowsOf(long request) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM exclusive_occurrence WHERE request_id = :r AND state IN ('HELD', 'ALLOCATED'))
                     + (SELECT count(*) FROM metered_allocation WHERE request_id = :r AND state IN ('HELD', 'ALLOCATED'))"""
                .replace(":r", Long.toString(request)), Integer.class);
    }

    /** The texts of the Member's notifications of this kind that name the Request, oldest first. */
    protected List<String> noticesAbout(long member, String kind, long request) {
        return jdbc.queryForList("""
                SELECT text FROM notification WHERE member_id = ? AND kind = ? AND text ~ ? ORDER BY id""",
                String.class, member, kind, "Request " + request + "\\M");
    }

    /** The Team's Charges or Earnings, oldest first. */
    protected List<BigDecimal> transactions(long team, String kind) {
        return jdbc.queryForList("SELECT amount FROM transaction WHERE team_id = ? AND kind = ? ORDER BY id",
                BigDecimal.class, team, kind);
    }
}
