package edu.cwru.openbench.allocation;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TimeWindow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

@DisplayName("Exclusive.Conflict: ExclusiveAllocator")
class ExclusiveAllocatorTest extends AllocationTest {

    private long rooms;
    private long roomA;
    private long roomB;
    private TimeWindow tuesdayAfternoon;

    @BeforeEach
    void createTwoRooms() {
        rooms = exclusiveType();
        roomA = room(rooms, "Room A");
        roomB = room(rooms, "Room B");
        tuesdayAfternoon = window(7, 14, 16);
    }

    @Test
    @DisplayName("A free room is satisfiable; hold writes HELD rows, commit makes them ALLOCATED")
    void evaluatesHoldsAndCommits() {
        LineSnapshot line = exclusiveLine(team, rooms, tuesdayAfternoon);

        assertThat(evaluate(line)).isEqualTo(Evaluation.satisfiable());
        hold(line);
        assertThat(occurrencesOf(line)).containsExactly(roomA + ":HELD");
        commit(line);
        assertThat(occurrencesOf(line)).containsExactly(roomA + ":ALLOCATED");
        assertThat(jdbc.queryForMap("SELECT team_id, request_id, starts_at, ends_at FROM exclusive_occurrence WHERE line_id = ?",
                line.lineId().value()))
                .containsEntry("team_id", team)
                .containsEntry("request_id", line.requestId().value())
                .containsEntry("starts_at", Timestamp.from(tuesdayAfternoon.start()))
                .containsEntry("ends_at", Timestamp.from(tuesdayAfternoon.end()));
    }

    @Test
    @DisplayName("A Hold blocks like an allocation, and cancel turns it EXPIRED, which frees the room")
    void cancelDropsTheHold() {
        LineSnapshot first = namedLine(team, rooms, tuesdayAfternoon, roomA);
        LineSnapshot second = namedLine(team, rooms, tuesdayAfternoon, roomA);
        hold(first);

        assertThat(evaluate(second).reason()).isEqualTo("NO_FREE_INSTANCE");
        cancel(first);
        cancel(first);

        assertThat(occurrencesOf(first)).containsExactly(roomA + ":EXPIRED");
        assertThat(evaluate(second).fits()).isTrue();
    }

    @Test
    @DisplayName("Without a named Resource, the free room with the fewest ALLOCATED hours in the Period wins")
    void picksTheLeastUsedFreeRoom() {
        // Room A has 2 ALLOCATED hours today (inside the current Period), Room B 1; a RELEASED one does not count.
        LineSnapshot history = exclusiveLine(team, rooms, window(0, 0, 3));
        occurrence(history, roomA, window(0, 0, 2), "ALLOCATED");
        occurrence(history, roomB, window(0, 2, 3), "ALLOCATED");
        occurrence(history, roomB, window(0, 0, 2), "RELEASED");

        LineSnapshot line = exclusiveLine(team, rooms, tuesdayAfternoon);
        commit(line);

        assertThat(occurrencesOf(line)).containsExactly(roomB + ":ALLOCATED");
    }

    @Test
    @DisplayName("Rooms with equal use go by id, and a busy room is skipped however little it is used")
    void breaksTiesByIdAndSkipsBusyRooms() {
        LineSnapshot first = exclusiveLine(team, rooms, tuesdayAfternoon);
        LineSnapshot second = exclusiveLine(team, rooms, tuesdayAfternoon);

        commit(first);
        commit(second);

        assertThat(occurrencesOf(first)).containsExactly(roomA + ":ALLOCATED");
        assertThat(occurrencesOf(second)).containsExactly(roomB + ":ALLOCATED");
    }

    @Test
    @DisplayName("A named Resource is the only candidate, even when another room is free")
    void honoursANamedResource() {
        LineSnapshot onB = namedLine(team, rooms, tuesdayAfternoon, roomB);
        commit(onB);
        assertThat(occurrencesOf(onB)).containsExactly(roomB + ":ALLOCATED");

        LineSnapshot alsoOnB = namedLine(team, rooms, window(7, 15, 17), roomB);
        Evaluation evaluation = evaluate(alsoOnB);

        assertThat(evaluation.reason()).isEqualTo("NO_FREE_INSTANCE");
        assertThat(evaluation.message()).startsWith("Room B is not free from ");
    }

    @Test
    @DisplayName("An overlapping window finds no free instance; back-to-back windows both fit")
    void overlapConflictsButBackToBackDoesNot() {
        long microscopes = exclusiveType();
        long microscope = room(microscopes, unique("Microscope"));
        LineSnapshot two = exclusiveLine(team, microscopes, window(7, 14, 16));
        commit(two);

        Evaluation overlapping = evaluate(exclusiveLine(team, microscopes, window(7, 15, 17)));
        assertThat(overlapping.outcome()).isEqualTo(Evaluation.Outcome.UNSATISFIABLE);
        assertThat(overlapping.reason()).isEqualTo("NO_FREE_INSTANCE");

        LineSnapshot before = exclusiveLine(team, microscopes, window(7, 12, 14));
        LineSnapshot after = exclusiveLine(team, microscopes, window(7, 16, 18));
        commit(before);
        commit(after);
        assertThat(occurrencesOf(before)).containsExactly(microscope + ":ALLOCATED");
        assertThat(occurrencesOf(after)).containsExactly(microscope + ":ALLOCATED");
    }

    @Test
    @DisplayName("A line for two rooms takes both or none")
    void takesEveryInstanceOrNone() {
        LineSnapshot both = exclusiveLine(team, rooms, tuesdayAfternoon, 2, Optional.empty(), clock.instant());
        commit(both);
        assertThat(occurrencesOf(both)).containsExactly(roomA + ":ALLOCATED", roomB + ":ALLOCATED");

        LineSnapshot partly = exclusiveLine(team, rooms, window(7, 15, 18), 2, Optional.empty(), clock.instant());
        assertThat(evaluate(partly).reason()).isEqualTo("NO_FREE_INSTANCE");
        assertThatThrownBy(() -> hold(partly))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("NO_FREE_INSTANCE"));
        assertThat(occurrencesOf(partly)).isEmpty();
    }

    @Test
    @DisplayName("Commit with no Hold first checks the fit again and inserts ALLOCATED rows, or refuses")
    void commitsWithoutAHold() {
        LineSnapshot first = namedLine(team, rooms, tuesdayAfternoon, roomA);
        commit(first);
        assertThat(occurrencesOf(first)).containsExactly(roomA + ":ALLOCATED");

        LineSnapshot clash = namedLine(team, rooms, tuesdayAfternoon, roomA);
        assertThatThrownBy(() -> commit(clash))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("NO_FREE_INSTANCE"));
        assertThat(occurrencesOf(clash)).isEmpty();
    }

    @Test
    @DisplayName("Holding or committing twice is a programming error")
    void refusesToHoldOrCommitTwice() {
        LineSnapshot line = exclusiveLine(team, rooms, tuesdayAfternoon);
        hold(line);
        assertThatThrownBy(() -> hold(line)).isInstanceOf(IllegalStateException.class);
        commit(line);
        assertThatThrownBy(() -> commit(line)).isInstanceOf(IllegalStateException.class);
        assertThat(occurrencesOf(line)).containsExactly(roomA + ":ALLOCATED");
    }

    @Test
    @DisplayName("Release marks the rows RELEASED, keeping history and freeing the room; releasing nothing is NOT_ALLOCATED")
    void releaseKeepsHistory() {
        LineSnapshot line = namedLine(team, rooms, tuesdayAfternoon, roomA);
        assertThatThrownBy(() -> release(line))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("NOT_ALLOCATED"));
        commit(line);

        release(line);

        assertThat(occurrencesOf(line)).containsExactly(roomA + ":RELEASED");
        assertThat(evaluate(namedLine(team, rooms, tuesdayAfternoon, roomA)).fits()).isTrue();
    }

    @Test
    @DisplayName("A retired room is never picked")
    void skipsRetiredRooms() {
        jdbc.update("UPDATE resource SET retired_at = now() WHERE id = ?", roomA);

        LineSnapshot line = exclusiveLine(team, rooms, tuesdayAfternoon);
        commit(line);
        assertThat(occurrencesOf(line)).containsExactly(roomB + ":ALLOCATED");
        assertThat(evaluate(namedLine(team, rooms, window(8, 9, 10), roomA)).reason()).isEqualTo("NO_FREE_INSTANCE");
    }

    @Test
    @DisplayName("Every operation refuses to run outside a transaction (Propagation.MANDATORY)")
    void needsTheCallersTransaction() {
        LineSnapshot line = exclusiveLine(team, rooms, tuesdayAfternoon);

        assertThatThrownBy(() -> allocatorOf(line).evaluate(line)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> allocatorOf(line).hold(line)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(occurrencesOf(line)).isEmpty();
    }

    private void occurrence(LineSnapshot line, long resource, TimeWindow window, String state) {
        jdbc.update("""
                INSERT INTO exclusive_occurrence (line_id, team_id, request_id, resource_id, starts_at, ends_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                line.lineId().value(), team, line.requestId().value(), resource, utc(window.start()), utc(window.end()),
                state);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
