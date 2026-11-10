package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which rows a Release gives back, without a database. */
@DisplayName("Release.Line: ReleasePlan")
class ReleasePlanTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");

    /** Three weekly occurrences, Wednesdays 10:00 to 12:00. */
    private static final List<ReleasePlan.Row> WEEKLY = List.of(
            new ReleasePlan.Row(1L, window(4, 10, 12), 1),
            new ReleasePlan.Row(2L, window(11, 10, 12), 1),
            new ReleasePlan.Row(3L, window(18, 10, 12), 1));

    @Test
    @DisplayName("The whole line gives back every occurrence not yet started; a running one stays")
    void givesBackWhatHasNotStarted() {
        Released released = ReleasePlan.of(WEEKLY, ReleasePart.wholeLine(), false, at(4, 11), ZONE, "rooms").released();

        assertThat(released.pieces()).extracting(ReleasedPiece::occurrence)
                .containsExactly(window(11, 10, 12), window(18, 10, 12));
        assertThat(released.nothingLeft()).isFalse();
    }

    @Test
    @DisplayName("NOT_ALLOCATED: the whole line once every occurrence has started, and no rows at all")
    void refusesWhenNothingIsLeft() {
        assertRefused(() -> ReleasePlan.of(WEEKLY, ReleasePart.wholeLine(), false, at(18, 10), ZONE, "rooms"), "NOT_ALLOCATED");
        assertRefused(() -> ReleasePlan.of(List.of(), ReleasePart.wholeLine(), false, at(1, 10), ZONE, "rooms"), "NOT_ALLOCATED");
    }

    @Test
    @DisplayName("An earlier end inside a running occurrence gives back its later hours; one before now is consumed")
    void endsEarlierInsideARunningOccurrence() {
        List<ReleasePlan.Row> one = List.of(new ReleasePlan.Row(1L, window(4, 10, 13), 1));

        Released released = ReleasePlan.of(one, new ReleasePart.EarlierEnd(at(4, 12)), false, at(4, 11), ZONE, "rooms")
                .released();

        assertThat(released.pieces()).containsExactly(new ReleasedPiece(window(4, 10, 13), window(4, 12, 13), 1));
        assertRefused(() -> ReleasePlan.of(one, new ReleasePart.EarlierEnd(at(4, 11)), false, at(4, 12), ZONE, "rooms"),
                "NOT_ALLOCATED");
    }

    @Test
    @DisplayName("An earlier end between two occurrences gives back the later ones whole")
    void endsEarlierBetweenOccurrences() {
        ReleasePlan plan = ReleasePlan.of(WEEKLY, new ReleasePart.EarlierEnd(at(12, 9)), false, at(1, 9), ZONE, "rooms");

        assertThat(plan.steps()).containsExactly(new ReleasePlan.Step.ReleaseRow(WEEKLY.get(2)));
    }

    @Test
    @DisplayName("Fewer units: Metered only, from 1 to one fewer than now, before the window starts")
    void keepsFewerUnits() {
        List<ReleasePlan.Row> metered = List.of(new ReleasePlan.Row(7L, window(4, 10, 14), 4));

        assertThat(ReleasePlan.of(metered, new ReleasePart.FewerUnits(1), true, at(1, 9), ZONE, "units").steps())
                .containsExactly(new ReleasePlan.Step.Reduce(metered.getFirst(), 1));
        assertRefused(() -> ReleasePlan.of(metered, new ReleasePart.FewerUnits(1), false, at(1, 9), ZONE, "rooms"),
                "VALIDATION_FAILED");
        assertRefused(() -> ReleasePlan.of(metered, new ReleasePart.FewerUnits(4), true, at(1, 9), ZONE, "units"),
                "QUANTITY_INVALID");
        assertRefused(() -> ReleasePlan.of(metered, new ReleasePart.FewerUnits(2), true, at(4, 10), ZONE, "units"),
                "NOT_ALLOCATED");
    }

    @Test
    @DisplayName("A part names each occurrence once and at least one")
    void validatesOccurrences() {
        assertThatThrownBy(() -> new ReleasePart.Occurrences(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReleasePart.Occurrences(List.of(at(4, 10), at(4, 10))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRefused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** November 2026 in company time. */
    private static Instant at(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0).atZone(ZONE).toInstant();
    }

    private static TimeWindow window(int day, int from, int to) {
        return new TimeWindow(at(day, from), at(day, to));
    }
}
