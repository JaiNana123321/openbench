package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Releases after a window has started, with a clock the test moves by hand (the [clock] step of
 * rel-schedule): the application's {@code MovableClock}, which {@code PostgresTest} resets after
 * each test.
 */
class ReleaseStartedTest extends ReleaseTest {

    /** H of the test case document: the first whole hour at least two hours from now. */
    private Instant h;

    @BeforeEach
    void computeH() {
        h = movableClock.instant().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
    }

    @Test
    @DisplayName("rel-schedule: 7.50 of 30.00 under seven days ahead; after H the remaining hours free with no credit back")
    @Verifies("rel-schedule")
    void followsTheLeadTime() throws Exception {
        LocalDate e = dayE();
        long[] l1 = submitted(alice, room, 1, at(e, 10), at(e, 12), roomA);
        long[] l2 = submitted(alice, room, 1, h, h.plus(Duration.ofHours(3)), roomB);
        assertThat(requestState(alice, l2[0])).isEqualTo("ALLOCATED");

        // 1. Release L1 in whole: Refund 7.50 (30.00 x 0.25), the lead time is under seven days.
        releaseWhole(alice, l1[0], l1[1])
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.refund").value(7.50));

        // 2. [clock] After H, Release L2's remaining hours from H+1: they free on Room B, and no credits return.
        movableClock.set(h.plus(Duration.ofMinutes(30)));
        release(alice, l2[0], l2[1], "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + h.plus(Duration.ofHours(1)) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line.state").value("PARTLY_RELEASED"))
                .andExpect(jsonPath("$.refund.pieces[0].fraction").value(0))
                .andExpect(jsonPath("$.refund.refund").value(0.00));
        assertThat(liveOn(roomB, h.plus(Duration.ofHours(1)), h.plus(Duration.ofHours(3)))).isEmpty();
        assertThat(liveOn(roomB, h, h.plus(Duration.ofHours(1)))).containsExactly(l2[1] + ":ALLOCATED");

        // Pass criteria: the Statement shows a Refund of 7.50 and no other credit for L2.
        statement(as(alice), platform).andExpect(jsonPath("$.totals.refunds").value(7.50));
        assertThat(refundsOf(l2[1])).isEmpty();
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
    }

    @Test
    @DisplayName("NOT_ALLOCATED once started: neither the whole line, nor its occurrence, nor fewer units, nor hours already consumed")
    void refusesWhatHasStarted() throws Exception {
        long[] room1 = submitted(alice, room, 1, h, h.plus(Duration.ofHours(3)), roomA);
        long[] gpus = submitted(alice, gpu, 4, h, h.plus(Duration.ofHours(3)), null);
        movableClock.set(h.plus(Duration.ofMinutes(90)));

        releaseWhole(alice, room1[0], room1[1])
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        release(alice, room1[0], room1[1], "{\"kind\": \"OCCURRENCES\", \"occurrences\": [\"" + h + "\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        release(alice, room1[0], room1[1], "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + h.plus(Duration.ofHours(1)) + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        release(alice, gpus[0], gpus[1], "{\"kind\": \"FEWER_UNITS\", \"newUnits\": 2}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        assertThat(refundsOf(room1[1])).isEmpty();
        assertThat(liveOn(roomA, h, h.plus(Duration.ofHours(3)))).containsExactly(room1[1] + ":ALLOCATED");
    }

    @Test
    @DisplayName("A waiting line whose start has passed is not allocated from freed capacity")
    void skipsAWaitingLineThatHasStarted() throws Exception {
        long[] a1 = submitted(alice, room, 1, h, h.plus(Duration.ofHours(3)), roomA);
        long[] b1 = submitted(bob, room, 1, h, h.plus(Duration.ofHours(2)), roomA);
        assertThat(requestState(bob, b1[0])).isEqualTo("WAITING");
        movableClock.set(h.plus(Duration.ofMinutes(30)));

        release(alice, a1[0], a1[1], "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + h.plus(Duration.ofHours(1)) + "\"}")
                .andExpect(status().isOk());

        assertThat(requestState(bob, b1[0])).isEqualTo("WAITING");
        assertThat(liveOn(roomA, h.plus(Duration.ofHours(1)), h.plus(Duration.ofHours(3)))).isEmpty();
    }

}
