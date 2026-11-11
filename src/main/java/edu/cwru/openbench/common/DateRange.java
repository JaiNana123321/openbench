package edu.cwru.openbench.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A range of days, inclusive at both ends (Loans, availability, Periods). JSON:
 * {@code {"from": "2026-11-02", "to": "2026-11-06"}}.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("A date range must not end before it starts.");
        }
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }

    public boolean contains(DateRange other) {
        return contains(other.from) && contains(other.to);
    }

    public boolean overlaps(DateRange other) {
        return !from.isAfter(other.to) && !other.from.isAfter(to);
    }

    /** The Monday-to-Friday days of the range: the person-days of a Loan over it. */
    public int weekdays() {
        int count = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    /** The days both ranges hold, or empty when they do not overlap. */
    public Optional<DateRange> intersection(DateRange other) {
        if (!overlaps(other)) {
            return Optional.empty();
        }
        return Optional.of(new DateRange(from.isAfter(other.from) ? from : other.from,
                to.isBefore(other.to) ? to : other.to));
    }
}
