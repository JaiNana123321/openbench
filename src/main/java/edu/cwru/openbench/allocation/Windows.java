package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Window arithmetic, pure (design Section 4.5, "Pure parts"). Windows are half-open
 * {@code [start, end)}, so back-to-back windows such as 12:00 to 14:00 and 14:00 to 16:00 do not
 * overlap, the same rule as the {@code tstzrange(..., '[)')} of the exclusion constraint.
 */
final class Windows {

    private static final long SECONDS_PER_HOUR = 3600;

    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("EEE d MMM uuuu, HH:mm", Locale.US);

    private Windows() {}

    static boolean overlap(TimeWindow a, TimeWindow b) {
        return a.overlaps(b);
    }

    /** How long the two windows overlap; zero when they do not. */
    static Duration overlapOf(TimeWindow a, TimeWindow b) {
        if (!overlap(a, b)) {
            return Duration.ZERO;
        }
        return Duration.between(
                a.start().isAfter(b.start()) ? a.start() : b.start(),
                a.end().isBefore(b.end()) ? a.end() : b.end());
    }

    /** Whether the window starts and ends on the hour, as every line's window does. */
    static boolean onWholeHours(TimeWindow window) {
        return window.start().getEpochSecond() % SECONDS_PER_HOUR == 0
                && window.start().getNano() == 0
                && window.end().getEpochSecond() % SECONDS_PER_HOUR == 0
                && window.end().getNano() == 0;
    }

    /** An instant for a message, such as "Tue 27 Oct 2026, 14:00", in the zone. */
    static String describe(Instant instant, ZoneId zone) {
        return MESSAGE_TIME.format(instant.atZone(zone));
    }

    /** A window for a message, such as "Tue 27 Oct 2026, 14:00 to Tue 27 Oct 2026, 16:00". */
    static String describe(TimeWindow window, ZoneId zone) {
        return describe(window.start(), zone) + " to " + describe(window.end(), zone);
    }

    /** The days {@code first} to {@code last}, both included, from midnight to midnight in the zone. */
    static TimeWindow ofDays(LocalDate first, LocalDate last, ZoneId zone) {
        return new TimeWindow(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
