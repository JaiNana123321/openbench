package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.inventory.Resource;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exclusive lines (requirements Exclusive.Conflict and Exclusive.Waitlist.Join): each instance is
 * held by at most one line at a time. A line fits when enough instances are free for its whole
 * window; without a named Resource it takes the free instances with the fewest ALLOCATED hours in
 * the current Period, ties by id. One row per occurrence and instance; Demo 1 has no recurrence, so
 * one occurrence, the window itself.
 *
 * <p>Instances are chosen under the type lock, so {@code exclusive_occurrence_no_overlap} never
 * fires in normal operation; it stops a double allocation by a path that skipped the lock.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExclusiveAllocator implements Allocator, Waitlistable {

    static final String NO_FREE_INSTANCE = "NO_FREE_INSTANCE";

    private final ExclusiveOccurrenceRepository occurrences;
    private final Waitlist waitlist;
    private final InventoryQueries inventory;
    private final SettingsService settings;
    private final Clock clock;

    ExclusiveAllocator(ExclusiveOccurrenceRepository occurrences, Waitlist waitlist, InventoryQueries inventory,
            SettingsService settings, Clock clock) {
        this.occurrences = occurrences;
        this.waitlist = waitlist;
        this.inventory = inventory;
        this.settings = settings;
        this.clock = clock;
    }

    /** Needs no transaction: the registry asks at start-up. */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public AllocationClass allocationClass() {
        return AllocationClass.EXCLUSIVE;
    }

    @Override
    public Evaluation evaluate(LineSnapshot line) {
        checkClass(line);
        return pick(line).isPresent()
                ? Evaluation.satisfiable()
                : Evaluation.unsatisfiable(NO_FREE_INSTANCE, noFreeInstance(line));
    }

    @Override
    public void hold(LineSnapshot line) {
        checkClass(line);
        if (!rowsOf(line, RowState.LIVE).isEmpty()) {
            throw new IllegalStateException("Line " + line.lineId().value() + " is already held or allocated.");
        }
        insert(line, RowState.HELD);
    }

    @Override
    public void commit(LineSnapshot line) {
        checkClass(line);
        List<ExclusiveOccurrenceRow> live = rowsOf(line, RowState.LIVE);
        if (live.isEmpty()) {
            insert(line, RowState.ALLOCATED);
            return;
        }
        if (live.stream().anyMatch(row -> row.state() == RowState.ALLOCATED)) {
            throw new IllegalStateException("Line " + line.lineId().value() + " is already allocated.");
        }
        live.forEach(row -> row.changeState(RowState.ALLOCATED));
        occurrences.flush();
    }

    @Override
    public void release(LineSnapshot line, ReleasePart part) {
        checkClass(line);
        List<ExclusiveOccurrenceRow> allocated = rowsOf(line, Set.of(RowState.ALLOCATED));
        if (allocated.isEmpty()) {
            throw new RuleViolation("NOT_ALLOCATED", "This line holds no allocated " + typeName(line) + ".");
        }
        switch (part) {
            case ReleasePart.WholeLine whole -> allocated.forEach(row -> row.changeState(RowState.RELEASED));
        }
        occurrences.flush();
    }

    @Override
    public void cancel(LineSnapshot line) {
        checkClass(line);
        rowsOf(line, Set.of(RowState.HELD)).forEach(row -> row.changeState(RowState.EXPIRED));
        occurrences.flush();
        waitlist.leave(line);
    }

    @Override
    public void join(LineSnapshot line) {
        checkClass(line);
        waitlist.join(line);
    }

    @Override
    public void leave(LineSnapshot line) {
        waitlist.leave(line);
    }

    @Override
    public List<WaitingLine> waitingFor(ResourceTypeId type, TimeWindow freed) {
        return waitlist.waitingFor(type, freed);
    }

    /** Inserts one row per chosen instance, after checking again that enough are free. */
    private void insert(LineSnapshot line, RowState state) {
        List<Long> chosen = pick(line)
                .orElseThrow(() -> new RuleViolation(NO_FREE_INSTANCE, noFreeInstance(line)));
        TimeWindow window = line.window();
        occurrences.saveAllAndFlush(chosen.stream()
                .map(resource -> new ExclusiveOccurrenceRow(line.lineId().value(), line.teamId().value(),
                        line.requestId().value(), resource, window.start(), window.end(), state))
                .toList());
    }

    /**
     * The instances the line would get: {@code quantity} free ones (or the named one) with the
     * fewest ALLOCATED hours in the current Period, ties by id; empty when too few are free.
     */
    private Optional<List<Long>> pick(LineSnapshot line) {
        List<Long> candidates = inventory.instancesOf(line.typeId()).stream()
                .map(Resource::id)
                .filter(id -> line.namedResource().map(id::equals).orElse(true))
                .map(ResourceId::value)
                .toList();
        if (candidates.size() < line.quantity()) {
            return Optional.empty();
        }
        Set<Long> free = new HashSet<>(candidates);
        free.removeAll(occurrences.busyAmong(candidates, RowState.LIVE, line.window().start(), line.window().end()));
        if (free.size() < line.quantity()) {
            return Optional.empty();
        }
        Map<Long, Duration> used = allocatedInCurrentPeriod(free);
        return Optional.of(free.stream()
                .sorted(Comparator.comparing((Long id) -> used.getOrDefault(id, Duration.ZERO))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(line.quantity())
                .toList());
    }

    /** ALLOCATED time per instance within the current Period; instances without any are absent. */
    private Map<Long, Duration> allocatedInCurrentPeriod(Set<Long> instances) {
        Period period = settings.currentPeriod();
        TimeWindow days = Windows.ofDays(period.startsOn(), period.endsOn(), clock.getZone());
        Map<Long, Duration> used = new HashMap<>();
        for (OccurrenceSpan span : occurrences.allocatedAmong(instances, days.start(), days.end())) {
            Duration inPeriod = Windows.overlapOf(new TimeWindow(span.startsAt(), span.endsAt()), days);
            used.merge(span.resourceId(), inPeriod, Duration::plus);
        }
        return used;
    }

    private List<ExclusiveOccurrenceRow> rowsOf(LineSnapshot line, Set<RowState> states) {
        return occurrences.findByLineIdAndStateIn(line.lineId().value(), states);
    }

    private String noFreeInstance(LineSnapshot line) {
        String when = Windows.describe(line.window(), clock.getZone());
        if (line.namedResource().isPresent()) {
            return inventory.resource(line.namedResource().get()).name() + " is not free from " + when + ".";
        }
        return line.quantity() == 1
                ? "No " + typeName(line) + " is free from " + when + "."
                : "Fewer than " + line.quantity() + " instances of " + typeName(line) + " are free from " + when + ".";
    }

    private String typeName(LineSnapshot line) {
        return inventory.type(line.typeId()).name();
    }

    private static void checkClass(LineSnapshot line) {
        if (line.allocationClass() != AllocationClass.EXCLUSIVE) {
            throw new IllegalArgumentException("Line " + line.lineId().value() + " is not Exclusive.");
        }
    }
}
