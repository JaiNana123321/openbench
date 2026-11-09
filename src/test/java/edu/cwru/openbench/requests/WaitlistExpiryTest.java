package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.jobs.DueWorkJob;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A waiting line leaves the waitlist when its window starts (SRS Exclusive.Waitlist.Expire), through
 * {@link DueWorkJob#run()} called with the movable Clock. Day E of the test case document is a weekday
 * two to six days ahead. Alice's Request holds Room A on E from 10:00 to 14:00; Bob's B1 waits for Room
 * A on E 10:00 to 12:00, and his B3 for Room A on E 12:00 to 14:00.
 */
class WaitlistExpiryTest extends DemoWorldTest {

    @Autowired
    private DueWorkJob dueWork;

    @Autowired
    private RequestLifecycleService lifecycle;

    private LocalDate e;
    private long b1;
    private long b1Line;
    private long b3Line;

    @BeforeEach
    void bobWaitsForRoomA() throws Exception {
        e = dayE();
        long allocated = createDraft(as(alice));
        line(as(alice), allocated, room, 1, at(e, 10), at(e, 14), roomA);
        submit(as(alice), allocated).andExpect(jsonPath("$.state").value("ALLOCATED"));

        b1 = createDraft(as(bob));
        b1Line = line(as(bob), b1, room, 1, at(e, 10), at(e, 12), roomA);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
        long b3 = createDraft(as(bob));
        b3Line = line(as(bob), b3, room, 1, at(e, 12), at(e, 14), roomA);
        submit(as(bob), b3).andExpect(jsonPath("$.state").value("WAITING"));
        detail(as(bob), b1).andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
    }

    @Test
    @DisplayName("exc-waitlist-expire: the waiting line leaves the waitlist at E 10:00 and its Request stays Waiting")
    @Verifies("exc-waitlist-expire")
    void leavesTheWaitlistWhenTheWindowStarts() throws Exception {
        // 1. Let E 10:00 pass: the due-work job runs. B1's line has left the waitlist; B1 stays Waiting.
        movableClock.set(at(e, 10));
        dueWork.run();

        String body = detail(as(bob), b1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty())
                .andExpect(jsonPath("$.lines[0].reason").value("WINDOW_STARTED"))
                .andReturn().getResponse().getContentAsString();
        // The expiry is a line event by the system.
        Map<String, Object> last = JsonPath.read(body, "$.history[-1]");
        assertThat(last).containsEntry("line", (int) b1Line).containsEntry("fromState", "WAITING")
                .containsEntry("toState", "WAITING").containsEntry("actor", null);

        // 2. Room A's waitlist (the timeline screen is #11, so read through the table): no line waits
        // for E 10:00-12:00.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM waitlist_entry WHERE type_id = ? AND starts_at < ? AND ends_at > ?""",
                Integer.class, room, utc(at(e, 12)), utc(at(e, 10)))).isZero();

        // Pass criteria: the line was never allocated after its window began.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ?", Integer.class, b1Line))
                .isZero();
    }

    @Test
    @DisplayName("expireWaitingLines never touches a line whose window has not started")
    void keepsALineWhoseWindowHasNotStarted() throws Exception {
        movableClock.set(at(e, 10).minusSeconds(1));
        dueWork.run();
        assertThat(onWaitlist(b1Line)).isTrue();

        movableClock.set(at(e, 10));
        dueWork.run();
        assertThat(onWaitlist(b1Line)).isFalse();
        assertThat(onWaitlist(b3Line)).isTrue();
        detail(as(bob), b1).andExpect(jsonPath("$.request.state").value("WAITING"));

        // A second run at the same time finds nothing more to do for B1.
        int events = jdbc.queryForObject("SELECT count(*) FROM request_event WHERE line_id = ?", Integer.class, b1Line);
        dueWork.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_event WHERE line_id = ?", Integer.class, b1Line))
                .isEqualTo(events);
    }

    @Test
    @DisplayName("A line that fails to expire is logged and does not stop the others")
    void movesOnPastAFailedLine() {
        // A waitlist entry whose line is no longer waiting: the expiry refuses it in its own transaction.
        jdbc.update("UPDATE request_line SET state = 'CANCELED' WHERE id = ?", b3Line);
        try {
            movableClock.set(at(e, 13));
            assertThat(lifecycle.expireWaitingLines(at(e, 13))).isGreaterThanOrEqualTo(1);

            assertThat(onWaitlist(b1Line)).isFalse();
            assertThat(onWaitlist(b3Line)).isTrue();
        } finally {
            // Leave no broken entry for later tests' runs.
            jdbc.update("DELETE FROM waitlist_entry WHERE line_id = ?", b3Line);
        }
    }

    private boolean onWaitlist(long line) {
        return jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id = ?", Integer.class, line) == 1;
    }

    /** Day E of the test case document: the first weekday at least two days after today. */
    private LocalDate dayE() {
        LocalDate day = LocalDate.now(clock).plusDays(2);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        return day;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
