package edu.cwru.openbench.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A half-open time window {@code [start, end)}: back-to-back windows such as 09:00 to 12:00 and
 * 12:00 to 13:00 do not overlap. JSON: {@code {"start": ..., "end": ...}} with ISO-8601 instants.
 */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("A time window must end after it starts.");
        }
    }

    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
