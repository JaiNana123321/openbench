package edu.cwru.openbench.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeeklyRecurrenceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static TimeWindow local(String start, String end) {
        return new TimeWindow(LocalDateTime.parse(start).atZone(NEW_YORK).toInstant(),
                LocalDateTime.parse(end).atZone(NEW_YORK).toInstant());
    }

    @Test
    @DisplayName("Without a recurrence a line has one occurrence, its window")
    void aLineWithoutARecurrenceHasItsWindowOnly() {
        TimeWindow window = local("2026-12-02T10:00", "2026-12-02T12:00");
        assertThat(WeeklyRecurrence.occurrencesOf(window, null, NEW_YORK)).containsExactly(window);
    }

    @Test
    @DisplayName("Three weekly occurrences: D, D+7, D+14 at the same hours")
    void repeatsTheWindowEveryWeek() {
        List<TimeWindow> occurrences = WeeklyRecurrence.occurrencesOf(
                local("2026-12-02T10:00", "2026-12-02T12:00"), new WeeklyRecurrence(3), NEW_YORK);
        assertThat(occurrences).containsExactly(
                local("2026-12-02T10:00", "2026-12-02T12:00"),
                local("2026-12-09T10:00", "2026-12-09T12:00"),
                local("2026-12-16T10:00", "2026-12-16T12:00"));
    }

    @Test
    @DisplayName("Exclusive.Conflict: 14:00 stays 14:00 in company time across the end of daylight saving time")
    void keepsTheLocalHoursAcrossADaylightSavingChange() {
        // Daylight saving time ends in New York on Sunday 1 November 2026.
        List<TimeWindow> occurrences = WeeklyRecurrence.occurrencesOf(
                local("2026-10-28T14:00", "2026-10-28T16:00"), new WeeklyRecurrence(2), NEW_YORK);
        assertThat(occurrences.get(1)).isEqualTo(local("2026-11-04T14:00", "2026-11-04T16:00"));
        // A week later in elapsed time is 13:00 company time; the occurrence is not.
        assertThat(occurrences.get(1).start()).isNotEqualTo(occurrences.get(0).start().plusSeconds(7 * 24 * 3600));
    }

    @Test
    @DisplayName("A recurrence with no occurrence is not expanded")
    void refusesARecurrenceWithoutOccurrences() {
        TimeWindow window = local("2026-12-02T10:00", "2026-12-02T12:00");
        assertThatThrownBy(() -> WeeklyRecurrence.occurrencesOf(window, new WeeklyRecurrence(0), NEW_YORK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
