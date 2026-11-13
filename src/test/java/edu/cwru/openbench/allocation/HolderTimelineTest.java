package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cutting a window into free, held, and allocated parts, without Spring (design Section 4.5, holders). */
@DisplayName("Inventory.View.Holder: HolderTimeline")
class HolderTimelineTest {

    private static final long PLATFORM = 1;
    private static final long RESEARCH = 2;
    private static final long A1 = 10;
    private static final long B1 = 20;

    private static Instant at(int hour) {
        return Instant.parse("2026-12-02T00:00:00Z").plusSeconds(hour * 3600L);
    }

    private static TimeWindow hours(int from, int to) {
        return new TimeWindow(at(from), at(to));
    }

    private static HolderTimeline.Piece piece(int from, int to, RowState state, long team, long request) {
        return new HolderTimeline.Piece(hours(from, to), state, team, request, false);
    }

    private static HolderTimeline.Segment free(int from, int to) {
        return new HolderTimeline.Segment(hours(from, to), HoldingState.FREE, null, null, false);
    }

    private static HolderTimeline.Segment part(int from, int to, HoldingState state, long team, long request) {
        return new HolderTimeline.Segment(hours(from, to), state, team, request, false);
    }

    @Test
    @DisplayName("inv-holder's window: free, allocated to A1, free, on Hold for B1, free")
    void cutsTheWindowIntoParts() {
        List<HolderTimeline.Segment> parts = HolderTimeline.of(hours(8, 18), List.of(
                piece(14, 16, RowState.HELD, RESEARCH, B1),
                piece(10, 12, RowState.ALLOCATED, PLATFORM, A1)));

        assertThat(parts).containsExactly(
                free(8, 10),
                part(10, 12, HoldingState.ALLOCATED, PLATFORM, A1),
                free(12, 14),
                part(14, 16, HoldingState.HELD, RESEARCH, B1),
                free(16, 18));
    }

    @Test
    @DisplayName("no live row: one free part over the whole window")
    void anEmptyWindowIsOneFreePart() {
        assertThat(HolderTimeline.of(hours(8, 18), List.of())).containsExactly(free(8, 18));
    }

    @Test
    @DisplayName("Released, Bumped, and Expired occurrences leave their hours free")
    void ignoresRowsThatAreNotLive() {
        List<HolderTimeline.Segment> parts = HolderTimeline.of(hours(8, 18), List.of(
                piece(9, 10, RowState.RELEASED, PLATFORM, A1),
                piece(10, 11, RowState.BUMPED, PLATFORM, A1),
                piece(11, 12, RowState.EXPIRED, RESEARCH, B1),
                piece(12, 13, RowState.ALLOCATED, RESEARCH, B1)));

        assertThat(parts).containsExactly(free(8, 12), part(12, 13, HoldingState.ALLOCATED, RESEARCH, B1), free(13, 18));
    }

    @Test
    @DisplayName("rows reaching past either end are cut to the window; rows outside it are left out")
    void cutsRowsToTheWindow() {
        List<HolderTimeline.Segment> parts = HolderTimeline.of(hours(8, 18), List.of(
                piece(6, 9, RowState.ALLOCATED, PLATFORM, A1),
                piece(17, 20, RowState.HELD, RESEARCH, B1),
                piece(2, 8, RowState.ALLOCATED, RESEARCH, B1),
                piece(18, 19, RowState.ALLOCATED, PLATFORM, A1)));

        assertThat(parts).containsExactly(
                part(8, 9, HoldingState.ALLOCATED, PLATFORM, A1),
                free(9, 17),
                part(17, 18, HoldingState.HELD, RESEARCH, B1));
    }

    @Test
    @DisplayName("touching rows of the same holder merge; a different Request, state, or mark does not")
    void mergesTouchingRowsOfTheSameHolder() {
        List<HolderTimeline.Segment> parts = HolderTimeline.of(hours(8, 18), List.of(
                piece(8, 10, RowState.ALLOCATED, PLATFORM, A1),
                piece(10, 12, RowState.ALLOCATED, PLATFORM, A1),
                piece(12, 13, RowState.ALLOCATED, PLATFORM, 11),
                piece(13, 14, RowState.HELD, PLATFORM, 11),
                new HolderTimeline.Piece(hours(14, 15), RowState.HELD, PLATFORM, 11, true),
                piece(16, 17, RowState.HELD, PLATFORM, 11)));

        assertThat(parts).containsExactly(
                part(8, 12, HoldingState.ALLOCATED, PLATFORM, A1),
                part(12, 13, HoldingState.ALLOCATED, PLATFORM, 11),
                part(13, 14, HoldingState.HELD, PLATFORM, 11),
                new HolderTimeline.Segment(hours(14, 15), HoldingState.HELD, PLATFORM, 11L, true),
                free(15, 16),
                part(16, 17, HoldingState.HELD, PLATFORM, 11),
                free(17, 18));
    }

    @Test
    @DisplayName("back-to-back parts of different holders stay apart and leave no free gap")
    void backToBackPartsLeaveNoGap() {
        List<HolderTimeline.Segment> parts = HolderTimeline.of(hours(10, 14), List.of(
                piece(10, 12, RowState.ALLOCATED, PLATFORM, A1),
                piece(12, 14, RowState.ALLOCATED, RESEARCH, B1)));

        assertThat(parts).containsExactly(
                part(10, 12, HoldingState.ALLOCATED, PLATFORM, A1),
                part(12, 14, HoldingState.ALLOCATED, RESEARCH, B1));
    }
}
