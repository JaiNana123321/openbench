package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Cuts a window of one Resource into free, held, and allocated parts (design Section 4.5,
 * {@code holders}), pure. Only live rows count: a Released, Bumped, or Expired occurrence leaves its
 * hours free. Rows are cut to the window, the hours between them are free, and touching parts of the
 * same holder (state, Team, Request, and Priority mark) merge into one. Windows are half-open, so a
 * row ending at 12:00 and one starting at 12:00 touch but do not overlap.
 */
final class HolderTimeline {

    private HolderTimeline() {}

    /** An occurrence on the Resource, as the timeline needs it. */
    record Piece(TimeWindow window, RowState state, long teamId, long requestId, boolean priority) {

        Piece {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(state, "state");
        }
    }

    /** One part of the window; a free part has a null Team and Request. */
    record Segment(TimeWindow window, HoldingState state, Long teamId, Long requestId, boolean priority) {

        boolean sameHolderAs(Segment other) {
            return state == other.state && Objects.equals(teamId, other.teamId)
                    && Objects.equals(requestId, other.requestId) && priority == other.priority;
        }
    }

    /**
     * The window's parts in order, covering it exactly. Live occurrences on one Resource never overlap
     * (the constraint {@code exclusive_occurrence_no_overlap}); should two, the earlier one wins.
     */
    static List<Segment> of(TimeWindow window, Collection<Piece> pieces) {
        List<Piece> live = pieces.stream()
                .filter(piece -> RowState.LIVE.contains(piece.state()) && piece.window().overlaps(window))
                .sorted(Comparator.comparing((Piece piece) -> piece.window().start()))
                .toList();
        List<Segment> parts = new ArrayList<>();
        Instant cursor = window.start();
        for (Piece piece : live) {
            Instant start = later(piece.window().start(), cursor);
            Instant end = earlier(piece.window().end(), window.end());
            if (!start.isBefore(end)) {
                continue;
            }
            if (cursor.isBefore(start)) {
                append(parts, free(cursor, start));
            }
            append(parts, new Segment(new TimeWindow(start, end), stateOf(piece.state()), piece.teamId(),
                    piece.requestId(), piece.priority()));
            cursor = end;
        }
        if (cursor.isBefore(window.end())) {
            append(parts, free(cursor, window.end()));
        }
        return List.copyOf(parts);
    }

    private static void append(List<Segment> parts, Segment next) {
        if (!parts.isEmpty()) {
            Segment last = parts.getLast();
            if (last.window().end().equals(next.window().start()) && last.sameHolderAs(next)) {
                parts.set(parts.size() - 1, new Segment(new TimeWindow(last.window().start(), next.window().end()),
                        last.state(), last.teamId(), last.requestId(), last.priority()));
                return;
            }
        }
        parts.add(next);
    }

    private static Segment free(Instant start, Instant end) {
        return new Segment(new TimeWindow(start, end), HoldingState.FREE, null, null, false);
    }

    private static HoldingState stateOf(RowState state) {
        return switch (state) {
            case HELD -> HoldingState.HELD;
            case ALLOCATED -> HoldingState.ALLOCATED;
            case RELEASED, BUMPED, EXPIRED -> throw new IllegalArgumentException("Only a live row holds a Resource.");
        };
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
