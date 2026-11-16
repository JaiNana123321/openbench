package edu.cwru.openbench.allocation;

import edu.cwru.openbench.allocation.HourlyLoad.Hour;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The choice of a tier Bump plan on a Metered pool (SRS Metered.Tier.Bump), pure. A line Bumps only
 * lines of another Request whose tier premium is strictly lower (no tier ranks lowest) and that have
 * no live row starting before the cutoff (now plus the type's bump cutoff). Bumped lines lose every
 * live row, since a Metered line is all or nothing.
 *
 * <p>The choice is greedy: while some hour of the window would exceed the Pool Capacity, it Bumps
 * the next line that has units in such an hour, lowest tier first, then the latest-submitted, then
 * the highest line id; then it gives back, latest pick first, every Bump the others make
 * unnecessary. This is not always the fewest lines (that is a set cover), but it never Bumps a line
 * that does not help, and never a higher tier where a lower one would do.
 */
final class TierBumpSearch {

    private static final Duration HOUR = Duration.ofHours(1);

    private TierBumpSearch() {}

    /** A live row of a candidate line within the window: its units over {@code [start, end)}. */
    record Span(int units, Instant start, Instant end) {

        /** The row's units in the hour starting at {@code hour}; 0 when it does not cover it. */
        int unitsIn(Instant hour) {
            return start.isBefore(hour.plus(HOUR)) && end.isAfter(hour) ? units : 0;
        }
    }

    /**
     * A line with live rows on the type in the window, as the plan sees it.
     *
     * @param tierPremium its rank; null ranks below every tier
     * @param submittedAt its Request's submission time; null counts as the earliest
     * @param firstStart the start of its earliest live row, anywhere
     * @param held whether every live row is HELD rather than ALLOCATED
     * @param spans its live rows that overlap the window
     */
    record Candidate(long lineId, long requestId, long teamId, BigDecimal tierPremium, Instant submittedAt,
            Instant firstStart, boolean held, List<Span> spans) {

        Candidate {
            spans = List.copyOf(spans);
        }

        int unitsIn(Instant hour) {
            return spans.stream().mapToInt(span -> span.unitsIn(hour)).sum();
        }
    }

    /**
     * The lines a tiered line of {@code ownRequest} and {@code team}, asking for {@code units} with
     * premium {@code premium}, Bumps so that every hour fits the Pool Capacity, by line id; empty when
     * the pool is not what keeps it out, when too few lines can be Bumped, or when the line would
     * still exceed its own Team's Quota.
     *
     * @param hours each hour of the window with the type's and the Team's live units, the line's own excluded
     */
    static Optional<List<Candidate>> choose(int units, BigDecimal premium, long ownRequest, long team,
            List<Hour> hours, int poolCapacity, Optional<Integer> quota, List<Candidate> candidates, Instant cutoff) {
        List<Candidate> eligible = candidates.stream()
                .filter(line -> bumpable(line, premium, ownRequest, cutoff))
                .sorted(lowestRankFirst())
                .toList();
        List<Candidate> chosen = new ArrayList<>();
        while (true) {
            List<Instant> overfull = shortHours(units, hours, poolCapacity, chosen);
            if (overfull.isEmpty()) {
                break;
            }
            Optional<Candidate> next = eligible.stream()
                    .filter(line -> !chosen.contains(line))
                    .filter(line -> overfull.stream().anyMatch(hour -> line.unitsIn(hour) > 0))
                    .findFirst();
            if (next.isEmpty()) {
                return Optional.empty();
            }
            chosen.add(next.get());
        }
        for (int i = chosen.size() - 1; i >= 0; i--) {
            List<Candidate> without = new ArrayList<>(chosen);
            without.remove(i);
            if (shortHours(units, hours, poolCapacity, without).isEmpty()) {
                chosen.remove(i);
            }
        }
        if (chosen.isEmpty() || quota.isPresent() && exceedsQuota(units, team, hours, quota.get(), chosen)) {
            return Optional.empty();
        }
        return Optional.of(chosen.stream().sorted(Comparator.comparingLong(Candidate::lineId)).toList());
    }

    /** A line of another Request, of a strictly lower rank, with no live row starting before the cutoff. */
    static boolean bumpable(Candidate line, BigDecimal premium, long ownRequest, Instant cutoff) {
        boolean lower = line.tierPremium() == null || line.tierPremium().compareTo(premium) < 0;
        return lower && line.requestId() != ownRequest && !line.firstStart().isBefore(cutoff);
    }

    /** No tier first, then the lowest premium; then the latest-submitted; then the highest line id. */
    private static Comparator<Candidate> lowestRankFirst() {
        return Comparator.comparing(Candidate::tierPremium, Comparator.nullsFirst(Comparator.<BigDecimal>naturalOrder()))
                .thenComparing(line -> line.submittedAt() == null ? Instant.MIN : line.submittedAt(),
                        Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(Candidate::lineId).reversed());
    }

    /** The hours in which the line would exceed the Pool Capacity with these lines Bumped. */
    private static List<Instant> shortHours(int units, List<Hour> hours, int poolCapacity, List<Candidate> bumped) {
        return hours.stream()
                .filter(hour -> hour.used() - freed(hour.start(), bumped) + units > poolCapacity)
                .map(Hour::start)
                .toList();
    }

    private static boolean exceedsQuota(int units, long team, List<Hour> hours, int quota, List<Candidate> bumped) {
        List<Candidate> ownTeam = bumped.stream().filter(line -> line.teamId() == team).toList();
        return hours.stream().anyMatch(hour -> hour.teamUsed() - freed(hour.start(), ownTeam) + units > quota);
    }

    private static int freed(Instant hour, List<Candidate> bumped) {
        return bumped.stream().mapToInt(line -> line.unitsIn(hour)).sum();
    }
}
