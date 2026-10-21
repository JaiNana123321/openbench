package edu.cwru.openbench.allocation;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Two transitions race for the last capacity (requirement QA-correct, ADR-0006). Two threads are
 * released together by a latch; each, in its own transaction, locks the type, evaluates its line,
 * and holds it if it fits, as {@code RequestLifecycleService} will. Exactly one may win, and no
 * hour may exceed the Pool Capacity. Without the type lock both evaluate the same free capacity:
 * the pool is oversold, or {@code exclusive_occurrence_no_overlap} refuses the second room.
 */
@DisplayName("QA-correct: two concurrent attempts for the last capacity")
class AllocationConcurrencyTest extends AllocationTest {

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    private long otherTeam;

    @BeforeEach
    void createASecondTeam() {
        otherTeam = testData().team(unique("Team"));
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @RepeatedTest(5)
    @DisplayName("the last free room goes to exactly one line")
    void theLastRoomGoesToOneLine() throws Exception {
        long rooms = exclusiveType();
        long room = room(rooms, "Room A");
        LineSnapshot ours = exclusiveLine(team, rooms, window(7, 14, 16));
        LineSnapshot theirs = exclusiveLine(otherTeam, rooms, window(7, 15, 17));

        List<String> outcomes = race(ours, theirs);

        assertThat(outcomes).as("outcomes").containsExactlyInAnyOrder("HELD", "NO_FREE_INSTANCE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exclusive_occurrence WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED')",
                Integer.class, room)).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("the last units of a pool go to exactly one line, and no hour exceeds the Pool Capacity")
    void theLastUnitsGoToOneLine() throws Exception {
        long gpus = meteredType();
        node(gpus, 4);
        node(gpus, 4);
        commit(meteredLine(team, gpus, 6, window(7, 9, 17)));
        LineSnapshot ours = meteredLine(team, gpus, 2, window(7, 10, 12));
        LineSnapshot theirs = meteredLine(otherTeam, gpus, 2, window(7, 11, 13));

        List<String> outcomes = race(ours, theirs);

        assertThat(outcomes).as("outcomes").containsExactlyInAnyOrder("HELD", "POOL_FULL");
        assertThat(peakHourlyUnits(gpus)).as("the busiest hour").isLessThanOrEqualTo(8);
    }

    /**
     * Runs both lines at once, each in its own transaction, and returns per line "HELD", the misfit
     * reason, or the exception that ended its transaction.
     */
    private List<String> race(LineSnapshot... lines) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(lines.length);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (LineSnapshot line : lines) {
            attempts.add(threads.submit(() -> {
                ready.countDown();
                go.await();
                return inTransaction(() -> {
                    lockTypeOf(line);
                    Evaluation evaluation = allocatorOf(line).evaluate(line);
                    if (!evaluation.fits()) {
                        return evaluation.reason();
                    }
                    allocatorOf(line).hold(line);
                    return "HELD";
                });
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> attempt : attempts) {
            try {
                outcomes.add(attempt.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                outcomes.add(e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            } catch (java.util.concurrent.TimeoutException e) {
                outcomes.add("timed out");
            }
        }
        return outcomes;
    }

    /** The largest sum of live units in any hour of the type, read from the rows themselves. */
    private int peakHourlyUnits(long type) {
        return jdbc.queryForObject("""
                SELECT coalesce(max(used), 0) FROM (
                    SELECT sum(m.units) AS used
                    FROM metered_allocation m
                    JOIN LATERAL generate_series(m.starts_at, m.ends_at - interval '1 hour', interval '1 hour') AS h(hour) ON true
                    WHERE m.type_id = ? AND m.state IN ('HELD', 'ALLOCATED')
                    GROUP BY h.hour) hours""", Integer.class, type);
    }
}
