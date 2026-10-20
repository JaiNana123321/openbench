package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.common.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class WindowsTest {

    private static final Instant NOON = Instant.parse("2026-10-27T16:00:00Z");

    private static TimeWindow hours(int fromHour, int toHour) {
        return new TimeWindow(NOON.plusSeconds(3600L * fromHour), NOON.plusSeconds(3600L * toHour));
    }

    @Test
    void overlappingWindowsOverlap() {
        assertThat(Windows.overlap(hours(0, 2), hours(1, 3))).isTrue();
        assertThat(Windows.overlap(hours(1, 3), hours(0, 2))).isTrue();
        assertThat(Windows.overlap(hours(0, 4), hours(1, 2))).isTrue();
    }

    @Test
    void backToBackWindowsDoNotOverlap() {
        assertThat(Windows.overlap(hours(0, 2), hours(2, 4))).isFalse();
        assertThat(Windows.overlap(hours(2, 4), hours(0, 2))).isFalse();
        assertThat(Windows.overlapOf(hours(0, 2), hours(2, 4))).isEqualTo(Duration.ZERO);
    }

    @Test
    void separateWindowsDoNotOverlap() {
        assertThat(Windows.overlap(hours(0, 1), hours(3, 4))).isFalse();
    }

    @Test
    void overlapOfIsTheSharedPart() {
        assertThat(Windows.overlapOf(hours(0, 3), hours(2, 5))).isEqualTo(Duration.ofHours(1));
        assertThat(Windows.overlapOf(hours(0, 5), hours(1, 3))).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void wholeHoursAreOnTheHour() {
        assertThat(Windows.onWholeHours(hours(0, 2))).isTrue();
        assertThat(Windows.onWholeHours(new TimeWindow(NOON.plusSeconds(1800), NOON.plusSeconds(7200)))).isFalse();
    }

    @Test
    void daysRunFromMidnightToMidnightInTheZone() {
        ZoneId newYork = ZoneId.of("America/New_York");
        TimeWindow october = Windows.ofDays(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), newYork);

        assertThat(october.start()).isEqualTo(Instant.parse("2026-10-01T04:00:00Z"));
        assertThat(october.end()).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));
    }
}
