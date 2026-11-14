package edu.cwru.openbench.allocation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The choice of a Bump plan (design Section 4.5, "Bump plan"; SRS Exclusive.Priority.Bump), pure:
 * the Priority line takes every free instance, and the rest of its quantity from the instances that
 * can be cleared, one at a time, each time the one needing the fewest new Bumps, ties toward the
 * latest-submitted lines, then by instance id. An instance can be cleared when every line blocking
 * it is a standard line of another Request with no occurrence starting before the cutoff (now plus
 * the type's bump cutoff). A line is Bumped whole, so a line blocking two chosen instances counts
 * once.
 */
final class BumpSearch {

    private BumpSearch() {}

    /**
     * A line with a live row on an instance, as the plan sees it.
     *
     * @param submittedAt its Request's submission time; null counts as the earliest
     * @param firstStart the start of its earliest live occurrence, on any instance
     * @param held whether its live rows are HELD rather than ALLOCATED
     */
    record Blocker(long lineId, long requestId, long teamId, boolean priority, Instant submittedAt, Instant firstStart,
            boolean held) {}

    /** An instance with the lines whose live rows overlap one of the Priority line's occurrences on it; none when free. */
    record Instance(long resourceId, List<Blocker> blockers) {}

    /** The instances the Priority line gets, by id, and the lines it Bumps, by line id. */
    record Choice(List<Long> instances, List<Blocker> bumped) {}

    /**
     * The plan for a Priority line of {@code ownRequest} asking for {@code quantity} instances among
     * {@code instances}; empty when enough are free (no Bump is needed) or too few can be cleared.
     */
    static Optional<Choice> choose(int quantity, List<Instance> instances, long ownRequest, Instant cutoff) {
        List<Long> chosen = new ArrayList<>();
        List<Instance> clearable = new ArrayList<>();
        for (Instance instance : instances) {
            if (instance.blockers().isEmpty()) {
                chosen.add(instance.resourceId());
            } else if (instance.blockers().stream().allMatch(line -> bumpable(line, ownRequest, cutoff))) {
                clearable.add(instance);
            }
        }
        if (chosen.size() >= quantity) {
            return Optional.empty();
        }
        Map<Long, Blocker> bumped = new LinkedHashMap<>();
        while (chosen.size() < quantity) {
            Optional<Instance> next = clearable.stream().min(cheapestGiven(bumped));
            if (next.isEmpty()) {
                return Optional.empty();
            }
            clearable.remove(next.get());
            chosen.add(next.get().resourceId());
            next.get().blockers().forEach(line -> bumped.putIfAbsent(line.lineId(), line));
        }
        return Optional.of(new Choice(chosen.stream().sorted().toList(),
                bumped.values().stream().sorted(Comparator.comparingLong(Blocker::lineId)).toList()));
    }

    /** A standard line of another Request with no occurrence starting before the cutoff. */
    static boolean bumpable(Blocker line, long ownRequest, Instant cutoff) {
        return !line.priority() && line.requestId() != ownRequest && !line.firstStart().isBefore(cutoff);
    }

    /**
     * Fewest lines not yet Bumped first; then the instance whose earliest-submitted new line was
     * submitted latest, so earlier submissions keep their instances; then by id.
     */
    private static Comparator<Instance> cheapestGiven(Map<Long, Blocker> bumped) {
        return Comparator.comparingLong((Instance instance) -> added(instance, bumped).size())
                .thenComparing((Instance instance) -> added(instance, bumped).stream()
                        .map(line -> line.submittedAt() == null ? Instant.MIN : line.submittedAt())
                        .min(Comparator.naturalOrder())
                        .orElse(Instant.MAX), Comparator.reverseOrder())
                .thenComparingLong(Instance::resourceId);
    }

    private static List<Blocker> added(Instance instance, Map<Long, Blocker> bumped) {
        return instance.blockers().stream().filter(line -> !bumped.containsKey(line.lineId())).toList();
    }
}
