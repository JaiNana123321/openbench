package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.inventory.Resource;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import edu.cwru.openbench.settings.TypePricing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * the current Period, ties by id. One row per occurrence and instance: a line with a weekly
 * recurrence needs the same instances free for every occurrence, and every occurrence lands on them,
 * all or none.
 *
 * <p>Instances are chosen under the type lock, so {@code exclusive_occurrence_no_overlap} never
 * fires in normal operation; it stops a double allocation by a path that skipped the lock.
 *
 * <p>A Priority line that does not fit may Bump standard lines ({@link Bumpable}; SRS
 * Exclusive.Priority.Bump): the plan is chosen by {@link BumpSearch}, and the Bumped rows are
 * flushed as BUMPED before the Priority line's rows are written (design Section 5, "Bump flush
 * order").
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExclusiveAllocator implements Allocator, Waitlistable, Bumpable {

    static final String NO_FREE_INSTANCE = "NO_FREE_INSTANCE";

    static final String INSIDE_BUMP_CUTOFF = "INSIDE_BUMP_CUTOFF";

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
    public Released release(LineSnapshot line, ReleasePart part) {
        checkClass(line);
        Map<Long, ExclusiveOccurrenceRow> allocated = new HashMap<>();
        rowsOf(line, Set.of(RowState.ALLOCATED)).forEach(row -> allocated.put(row.id(), row));
        ReleasePlan plan = plan(line, part, allocated.values());
        for (ReleasePlan.Step step : plan.steps()) {
            ExclusiveOccurrenceRow row = allocated.get(step.row().id());
            switch (step) {
                case ReleasePlan.Step.ReleaseRow whole -> row.changeState(RowState.RELEASED);
                case ReleasePlan.Step.Shorten shorten -> {
                    occurrences.save(row.tailFrom(shorten.newEnd()));
                    row.endAt(shorten.newEnd());
                }
                case ReleasePlan.Step.Reduce reduce -> throw new IllegalStateException("An Exclusive row has no units.");
            }
        }
        occurrences.flush();
        return plan.released();
    }

    /** What the part would give back, without writing: the plan {@link #release} applies. */
    Released preview(LineSnapshot line, ReleasePart part) {
        checkClass(line);
        return plan(line, part, rowsOf(line, Set.of(RowState.ALLOCATED))).released();
    }

    private ReleasePlan plan(LineSnapshot line, ReleasePart part, Collection<ExclusiveOccurrenceRow> rows) {
        return ReleasePlan.of(
                rows.stream().map(row -> new ReleasePlan.Row(row.id(), row.window(), 1)).toList(),
                part, false, clock.instant(), clock.getZone(), typeName(line));
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
        insertOn(line, chosen, state);
    }

    /** One row per occurrence on each of these instances, flushed at once. */
    private void insertOn(LineSnapshot line, List<Long> instances, RowState state) {
        occurrences.saveAllAndFlush(instances.stream()
                .flatMap(resource -> line.occurrences().stream()
                        .map(window -> new ExclusiveOccurrenceRow(line.lineId().value(), line.teamId().value(),
                                line.requestId().value(), resource, window.start(), window.end(), state,
                                line.priority(), line.submittedAt())))
                .toList());
    }

    /**
     * The instances the line would get: {@code quantity} free ones (or the named one) with the
     * fewest ALLOCATED hours in the current Period, ties by id; empty when too few are free. An
     * instance is free when no live row on it overlaps any of the line's occurrences.
     */
    private Optional<List<Long>> pick(LineSnapshot line) {
        List<Long> candidates = candidates(line);
        if (candidates.size() < line.quantity()) {
            return Optional.empty();
        }
        Set<Long> free = new HashSet<>(candidates);
        for (TimeWindow occurrence : line.occurrences()) {
            free.removeAll(occurrences.busyAmong(candidates, RowState.LIVE, occurrence.start(), occurrence.end()));
        }
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

    /** The instances the line may have: the named one, or every instance of its type, by id. */
    private List<Long> candidates(LineSnapshot line) {
        return inventory.instancesOf(line.typeId()).stream()
                .map(Resource::id)
                .filter(id -> line.namedResource().map(id::equals).orElse(true))
                .map(ResourceId::value)
                .toList();
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
        if (line.occurrences().size() > 1) {
            when += " in every one of its " + line.occurrences().size() + " weekly occurrences";
        }
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

    // ---------------------------------------------------------------- Bumps

    /**
     * Per instance, the lines whose live rows overlap one of the Priority line's occurrences, each
     * with its earliest live occurrence on any instance; then {@link BumpSearch} chooses, with the
     * cutoff at now plus the type's bump cutoff (none when the type has no pricing settings).
     */
    @Override
    public Optional<BumpPlan> planBump(LineSnapshot line) {
        checkClass(line);
        if (!line.priority() || pick(line).isPresent()) {
            return Optional.empty();
        }
        List<Long> candidates = candidates(line);
        Map<Long, Map<Long, ExclusiveOccurrenceRow>> blockingByInstance = new LinkedHashMap<>();
        candidates.forEach(resource -> blockingByInstance.put(resource, new LinkedHashMap<>()));
        for (TimeWindow occurrence : line.occurrences()) {
            for (ExclusiveOccurrenceRow row : occurrences.liveAmong(candidates, RowState.LIVE, occurrence.start(),
                    occurrence.end())) {
                blockingByInstance.get(row.resourceId()).putIfAbsent(row.lineId(), row);
            }
        }
        Map<Long, BumpSearch.Blocker> blockers = new HashMap<>();
        List<BumpSearch.Instance> instances = new ArrayList<>();
        blockingByInstance.forEach((resource, rows) -> instances.add(new BumpSearch.Instance(resource,
                rows.keySet().stream().map(lineId -> blockers.computeIfAbsent(lineId, this::blocker)).toList())));
        Instant cutoff = clock.instant().plus(bumpCutoff(line.typeId()));
        return BumpSearch.choose(line.quantity(), instances, line.requestId().value(), cutoff)
                .map(choice -> new BumpPlan(line,
                        choice.instances().stream().map(ResourceId::new).toList(),
                        choice.bumped().stream().map(bumped -> new BumpPlan.BumpedLine(
                                new RequestLineId(bumped.lineId()), new RequestId(bumped.requestId()),
                                new TeamId(bumped.teamId()), bumped.held())).toList()));
    }

    /**
     * Plans again and requires the same plan; then marks every live row of the Bumped lines BUMPED
     * and flushes before the Priority line's rows are inserted as HELD, since Hibernate would run
     * the inserts first and meet the old live rows (design Section 5, "Bump flush order").
     */
    @Override
    public void applyBump(BumpPlan plan) {
        LineSnapshot line = plan.priorityLine();
        checkClass(line);
        if (!planBump(line).equals(Optional.of(plan))) {
            throw new RuleViolation(INSIDE_BUMP_CUTOFF, "The Bump planned for line " + line.lineId().value()
                    + " no longer holds: a line it would Bump is now inside the bump cutoff or has changed.");
        }
        if (!rowsOf(line, RowState.LIVE).isEmpty()) {
            throw new IllegalStateException("Line " + line.lineId().value() + " is already held or allocated.");
        }
        for (BumpPlan.BumpedLine bumped : plan.bumped()) {
            occurrences.findByLineIdAndStateIn(bumped.line().value(), RowState.LIVE)
                    .forEach(row -> row.changeState(RowState.BUMPED));
        }
        occurrences.flush();
        insertOn(line, plan.instances().stream().map(ResourceId::value).toList(), RowState.HELD);
    }

    /** A blocking line as the plan sees it, from all its live rows. */
    private BumpSearch.Blocker blocker(Long lineId) {
        List<ExclusiveOccurrenceRow> rows = occurrences.findByLineIdAndStateIn(lineId, RowState.LIVE);
        ExclusiveOccurrenceRow first = rows.stream()
                .min(Comparator.comparing((ExclusiveOccurrenceRow row) -> row.window().start()))
                .orElseThrow();
        return new BumpSearch.Blocker(lineId, first.requestId(), first.teamId(),
                rows.stream().anyMatch(ExclusiveOccurrenceRow::priority), first.submittedAt(), first.window().start(),
                rows.stream().allMatch(row -> row.state() == RowState.HELD));
    }

    private Duration bumpCutoff(ResourceTypeId type) {
        return settings.current().pricingOf(type).map(TypePricing::bumpCutoff).orElse(Duration.ZERO);
    }
}
