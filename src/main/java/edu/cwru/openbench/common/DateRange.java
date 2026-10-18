package edu.cwru.openbench.common;

import java.time.LocalDate;
import java.util.Objects;

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
}
