package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Allocator#release} with each {@link ReleasePart} on PostgreSQL. A weekly line cannot be
 * submitted yet (#19), so its three occurrence rows are written directly, as the Exclusive
 * allocator would write them: one ALLOCATED row per occurrence on the same room.
 */
@DisplayName("Release.Line: given-back occurrences, an earlier end, and fewer units")
class ReleasePartsTest extends AllocationTest {

    private long rooms;
    private long roomA;
    private long roomB;
    private long gpus;

    @BeforeEach
    void createRoomsAndAPool() {
        rooms = exclusiveType();
        roomA = room(rooms, "Room A");
        roomB = room(rooms, "Room B");
        gpus = meteredType();
        node(gpus, 8);
    }

    @Test
    @DisplayName("rel-partial, step 3: giving back the occurrence on D+14 keeps D and D+7 on Room B")
    @Verifies(value = "rel-partial", partial = true)
    void givesBackOneOccurrenceOfAWeeklyLine() {
        List<TimeWindow> weekly = List.of(window(14, 10, 12), window(21, 10, 12), window(28, 10, 12));
        LineSnapshot l2 = weeklyLine(roomB, weekly);

        Released released = release(l2, new ReleasePart.Occurrences(List.of(weekly.get(2).start())));

        assertThat(released.pieces()).containsExactly(new ReleasedPiece(weekly.get(2), weekly.get(2), 1));
        assertThat(released.nothingLeft()).isFalse();
        assertThat(rowsOf(l2)).containsExactly(
                roomB + " " + weekly.get(0).start() + " ALLOCATED",
                roomB + " " + weekly.get(1).start() + " ALLOCATED",
                roomB + " " + weekly.get(2).start() + " RELEASED");
        assertThat(queries.allocatedParts(l2.lineId())).containsExactly(
                new AllocatedPart(weekly.get(0), 1), new AllocatedPart(weekly.get(1), 1));
        assertThat(evaluate(namedLine(team, rooms, weekly.get(2), roomB)).fits()).isTrue();
        assertThat(evaluate(namedLine(team, rooms, weekly.get(1), roomB)).fits()).isFalse();
    }

    @Test
    @DisplayName("An earlier end gives back the later occurrences whole and shortens the one it falls in, on the same room")
    void endsAWeeklyLineEarlier() {
        List<TimeWindow> weekly = List.of(window(14, 10, 12), window(21, 10, 12), window(28, 10, 12));
        LineSnapshot line = weeklyLine(roomA, weekly);

        Released released = release(line, new ReleasePart.EarlierEnd(at(21, 11)));

        assertThat(released.pieces()).containsExactly(
                new ReleasedPiece(weekly.get(1), window(21, 11, 12), 1),
                new ReleasedPiece(weekly.get(2), weekly.get(2), 1));
        assertThat(queries.allocatedParts(line.lineId())).containsExactly(
                new AllocatedPart(weekly.get(0), 1), new AllocatedPart(window(21, 10, 11), 1));
        assertThat(jdbc.queryForList("""
                SELECT resource_id || ' ' || state FROM exclusive_occurrence WHERE line_id = ? AND state = 'RELEASED'
                ORDER BY starts_at""", String.class, line.lineId().value()))
                .containsExactly(roomA + " RELEASED", roomA + " RELEASED");
        assertThat(evaluate(namedLine(team, rooms, window(21, 11, 12), roomA)).fits()).isTrue();
    }

    @Test
    @DisplayName("A line on two rooms gives back both rooms of an occurrence as one piece of 2 instances")
    void countsInstancesPerOccurrence() {
        LineSnapshot pair = exclusiveLine(team, rooms, window(14, 10, 12), 2, Optional.empty(), clock.instant());
        commit(pair);

        Released released = release(pair, ReleasePart.wholeLine());

        assertThat(released.pieces()).containsExactly(new ReleasedPiece(window(14, 10, 12), window(14, 10, 12), 2));
        assertThat(released.nothingLeft()).isTrue();
        assertThat(occurrencesOf(pair)).containsExactlyInAnyOrder(roomA + ":RELEASED", roomB + ":RELEASED");
    }

    @Test
    @DisplayName("A Metered line keeps fewer units or ends earlier, and the units given back fit another line")
    void releasesUnitsAndHours() {
        LineSnapshot line = meteredLine(team, gpus, 6, window(14, 10, 14));
        commit(line);
        LineSnapshot other = meteredLine(team, gpus, 4, window(14, 10, 11));
        assertThat(evaluate(other).reason()).isEqualTo("POOL_FULL");

        Released fewer = release(line, new ReleasePart.FewerUnits(4));
        Released earlier = release(line, new ReleasePart.EarlierEnd(at(14, 12)));

        assertThat(fewer.pieces()).containsExactly(new ReleasedPiece(window(14, 10, 14), window(14, 10, 14), 2));
        assertThat(earlier.pieces()).containsExactly(new ReleasedPiece(window(14, 10, 14), window(14, 12, 14), 4));
        assertThat(queries.allocatedParts(line.lineId())).containsExactly(new AllocatedPart(window(14, 10, 12), 4));
        assertThat(jdbc.queryForList("SELECT units || ' ' || state FROM metered_allocation WHERE line_id = ? ORDER BY id",
                String.class, line.lineId().value())).containsExactly("4 ALLOCATED", "2 RELEASED", "4 RELEASED");
        assertThat(evaluate(other).fits()).isTrue();
        assertThat(evaluate(meteredLine(team, gpus, 5, window(14, 10, 11))).reason()).isEqualTo("POOL_FULL");
    }

    @Test
    @DisplayName("The preview gives the same pieces as the Release and writes nothing")
    void previewsWithoutWriting() {
        LineSnapshot line = meteredLine(team, gpus, 6, window(14, 10, 14));
        commit(line);

        Released preview = inTransaction(() -> queries.previewRelease(line, new ReleasePart.FewerUnits(1)));

        assertThat(preview.pieces()).containsExactly(new ReleasedPiece(window(14, 10, 14), window(14, 10, 14), 5));
        assertThat(meteredStatesOf(line)).containsExactly("ALLOCATED");
        assertThatThrownBy(() -> inTransaction(() -> queries.previewRelease(line, new ReleasePart.FewerUnits(6))))
                .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("QUANTITY_INVALID"));
    }

    @Test
    @DisplayName("NOT_ALLOCATED: an occurrence the line does not hold, or one already given back")
    void refusesOccurrencesOutsideTheAllocation() {
        List<TimeWindow> weekly = List.of(window(14, 10, 12), window(21, 10, 12));
        LineSnapshot line = weeklyLine(roomA, weekly);
        release(line, new ReleasePart.Occurrences(List.of(weekly.get(1).start())));

        for (Instant start : List.of(weekly.get(1).start(), at(14, 11), at(35, 10))) {
            assertThatThrownBy(() -> release(line, new ReleasePart.Occurrences(List.of(start))))
                    .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("NOT_ALLOCATED"));
        }
        assertThat(queries.allocatedParts(line.lineId())).containsExactly(new AllocatedPart(weekly.get(0), 1));
    }

    // ---------------------------------------------------------------- helpers

    /** A line with one ALLOCATED row per occurrence on the room, written as the allocator writes them. */
    private LineSnapshot weeklyLine(long resource, List<TimeWindow> occurrences) {
        LineSnapshot line = namedLine(team, rooms, occurrences.getFirst(), resource);
        for (TimeWindow occurrence : occurrences) {
            jdbc.update("""
                    INSERT INTO exclusive_occurrence (line_id, team_id, request_id, resource_id, starts_at, ends_at, state)
                    VALUES (?, ?, ?, ?, ?, ?, 'ALLOCATED')""", line.lineId().value(), team, line.requestId().value(),
                    resource, occurrence.start().atOffset(ZoneOffset.UTC), occurrence.end().atOffset(ZoneOffset.UTC));
        }
        return line;
    }

    private Released release(LineSnapshot line, ReleasePart part) {
        return inTransaction(() -> {
            lockTypeOf(line);
            return allocatorOf(line).release(line, part);
        });
    }

    /** The line's rows as "resource start state", by start. */
    private List<String> rowsOf(LineSnapshot line) {
        return jdbc.query("SELECT resource_id, starts_at, state FROM exclusive_occurrence WHERE line_id = ? ORDER BY starts_at",
                (rs, n) -> rs.getLong(1) + " " + rs.getTimestamp(2).toInstant() + " " + rs.getString(3),
                line.lineId().value());
    }
}
