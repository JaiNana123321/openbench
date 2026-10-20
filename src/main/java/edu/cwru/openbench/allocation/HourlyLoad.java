package edu.cwru.openbench.allocation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The Metered fit, pure (design Section 4.5): given the live units summed per hour of a window, the
 * earliest hour in which a line's units would exceed the Pool Capacity or the Team's Quota. The pool
 * is checked over the whole window first, then the Quota, so a line that fails both is reported
 * {@code POOL_FULL}.
 */
final class HourlyLoad {

    static final String POOL_FULL = "POOL_FULL";
    static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

    /** One hour of the window: the type's live units and the requesting Team's share of them. */
    record Hour(Instant start, int used, int teamUsed) {}

    /** The first failing hour, and by how many units the line overshoots there. */
    record Excess(String reason, Instant hour, int unitsOver, int limit) {}

    private HourlyLoad() {}

    /** The first hour that fails, pool first; empty when the line fits with full units in every hour. */
    static Optional<Excess> firstExcess(List<Hour> hours, int units, int poolCapacity, Optional<Integer> quota) {
        for (Hour hour : hours) {
            if (hour.used() + units > poolCapacity) {
                return Optional.of(new Excess(POOL_FULL, hour.start(), hour.used() + units - poolCapacity, poolCapacity));
            }
        }
        if (quota.isPresent()) {
            int max = quota.get();
            for (Hour hour : hours) {
                if (hour.teamUsed() + units > max) {
                    return Optional.of(new Excess(QUOTA_EXCEEDED, hour.start(), hour.teamUsed() + units - max, max));
                }
            }
        }
        return Optional.empty();
    }

    /** The first hour whose live units exceed the limit, for a capacity reduction. */
    static Optional<Instant> firstAbove(List<Hour> hours, int limit) {
        return hours.stream().filter(hour -> hour.used() > limit).map(Hour::start).findFirst();
    }

    /** The largest hourly sum; 0 for no hours. */
    static int peak(List<Hour> hours) {
        return hours.stream().mapToInt(Hour::used).max().orElse(0);
    }
}
