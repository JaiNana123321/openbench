package edu.cwru.openbench.settings;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Set;

/**
 * A Price Band of a Resource Type: on the given weekdays, the hours from {@code start} (inclusive)
 * to {@code end} (exclusive) cost {@code multiplier} times the Unit Price. An {@code end} of 00:00 is
 * the midnight that ends the day, so 00:00 to 00:00 is the whole day. Times are in the company zone.
 */
public record PriceBand(Set<DayOfWeek> weekdays, LocalTime start, LocalTime end, BigDecimal multiplier) {

    public PriceBand {
        weekdays = Set.copyOf(weekdays);
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(multiplier, "multiplier");
    }

    /** Whether the band covers this local time: its weekday is listed and its time of day is in the band. */
    public boolean covers(LocalDateTime time) {
        if (!weekdays.contains(time.getDayOfWeek())) {
            return false;
        }
        LocalTime clock = time.toLocalTime();
        boolean toMidnight = end.equals(LocalTime.MIDNIGHT);
        return !clock.isBefore(start) && (toMidnight || clock.isBefore(end));
    }
}
