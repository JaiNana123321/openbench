package edu.cwru.openbench.allocation;

import edu.cwru.openbench.allocation.HourlyLoad.Excess;
import edu.cwru.openbench.allocation.HourlyLoad.Hour;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.settings.SettingsService;
import edu.cwru.openbench.settings.TypePricing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metered lines (requirements Metered.Line.Form and Metered.Pool.Check): a line holds its units
 * concurrently over its whole window, in one {@code metered_allocation} row. It fits when, in every
 * hour, the type's live units plus its own stay within the Pool Capacity and, if the Team has a
 * Quota on the type, the Team's live units plus its own stay within the Quota. Holds count exactly
 * like allocations.
 *
 * <p>No constraint can sum rows, so this is correct only because the sums are read after the type
 * lock at READ COMMITTED (design Section 4.5, "Isolation"); the isolation level is never raised.
 *
 * <p>A line with a service tier that does not fit may Bump lines of lower tiers ({@link Bumpable};
 * SRS Metered.Tier.Bump): the plan is chosen by {@link TierBumpSearch}, and the Bumped rows are
 * flushed as BUMPED before the tiered line's row is written. A type without a bump cutoff never Bumps.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class MeteredAllocator implements Allocator, Waitlistable, Bumpable {

    static final String INSIDE_BUMP_CUTOFF = "INSIDE_BUMP_CUTOFF";

    private final MeteredAllocationRepository allocations;
    private final HourlySums sums;
    private final Waitlist waitlist;
    private final InventoryQueries inventory;
    private final SettingsService settings;
    private final Clock clock;

    MeteredAllocator(MeteredAllocationRepository allocations, HourlySums sums, Waitlist waitlist,
            InventoryQueries inventory, SettingsService settings, Clock clock) {
        this.allocations = allocations;
        this.sums = sums;
        this.waitlist = waitlist;
        this.inventory = inventory;
        this.settings = settings;
        this.clock = clock;
    }

    /** Needs no transaction: the registry asks at start-up. */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public AllocationClass allocationClass() {
        return AllocationClass.METERED;
    }

    @Override
    public Evaluation evaluate(LineSnapshot line) {
        checkClass(line);
        return excess(line)
                .map(excess -> Evaluation.unsatisfiable(excess.reason(), describe(line, excess)))
                .orElseGet(Evaluation::satisfiable);
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
        List<MeteredAllocationRow> live = rowsOf(line, RowState.LIVE);
        if (live.isEmpty()) {
            insert(line, RowState.ALLOCATED);
            return;
        }
        if (live.stream().anyMatch(row -> row.state() == RowState.ALLOCATED)) {
            throw new IllegalStateException("Line " + line.lineId().value() + " is already allocated.");
        }
        live.forEach(row -> row.changeState(RowState.ALLOCATED));
        allocations.flush();
    }

    @Override
    public Released release(LineSnapshot line, ReleasePart part) {
        checkClass(line);
        Map<Long, MeteredAllocationRow> allocated = new HashMap<>();
        rowsOf(line, Set.of(RowState.ALLOCATED)).forEach(row -> allocated.put(row.id(), row));
        ReleasePlan plan = plan(line, part, allocated.values());
        for (ReleasePlan.Step step : plan.steps()) {
            MeteredAllocationRow row = allocated.get(step.row().id());
            switch (step) {
                case ReleasePlan.Step.ReleaseRow whole -> row.changeState(RowState.RELEASED);
                case ReleasePlan.Step.Shorten shorten -> {
                    allocations.save(row.tailFrom(shorten.newEnd()));
                    row.endAt(shorten.newEnd());
                }
                case ReleasePlan.Step.Reduce reduce -> {
                    allocations.save(row.unitsGivenBack(reduce.newUnits()));
                    row.keepUnits(reduce.newUnits());
                }
            }
        }
        allocations.flush();
        return plan.released();
    }

    /** What the part would give back, without writing: the plan {@link #release} applies. */
    Released preview(LineSnapshot line, ReleasePart part) {
        checkClass(line);
        return plan(line, part, rowsOf(line, Set.of(RowState.ALLOCATED))).released();
    }

    private ReleasePlan plan(LineSnapshot line, ReleasePart part, Collection<MeteredAllocationRow> rows) {
        return ReleasePlan.of(
                rows.stream().map(row -> new ReleasePlan.Row(row.id(), row.window(), row.units())).toList(),
                part, true, clock.instant(), clock.getZone(), "units of " + inventory.type(line.typeId()).name());
    }

    @Override
    public void cancel(LineSnapshot line) {
        checkClass(line);
        rowsOf(line, Set.of(RowState.HELD)).forEach(row -> row.changeState(RowState.EXPIRED));
        allocations.flush();
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

    /** Inserts the line's one row after checking the fit again. */
    private void insert(LineSnapshot line, RowState state) {
        excess(line).ifPresent(excess -> {
            throw new RuleViolation(excess.reason(), describe(line, excess), Map.of("earliestHour", excess.hour()));
        });
        allocations.saveAndFlush(new MeteredAllocationRow(line.lineId().value(), line.teamId().value(),
                line.requestId().value(), line.typeId().value(), line.units(), line.window().start(),
                line.window().end(), state).ranked(line.tierPremium(), line.submittedAt()));
    }

    /** The first hour that fails, the pool checked before the Quota; empty when the line fits. */
    private Optional<Excess> excess(LineSnapshot line) {
        if (!Windows.onWholeHours(line.window())) {
            throw new IllegalArgumentException("A Metered line's window starts and ends on the hour.");
        }
        int poolCapacity = inventory.poolCapacity(line.typeId());
        Optional<Integer> quota = settings.current().quotaOf(line.teamId(), line.typeId());
        return HourlyLoad.firstExcess(
                sums.forWindow(line.typeId(), line.teamId(), line.window()), line.units(), poolCapacity, quota);
    }

    private String describe(LineSnapshot line, Excess excess) {
        String type = inventory.type(line.typeId()).name();
        String at = Windows.describe(excess.hour(), clock.getZone());
        String units = excess.unitsOver() == 1 ? "1 unit" : excess.unitsOver() + " units";
        return excess.reason().equals(HourlyLoad.POOL_FULL)
                ? "The " + type + " pool of " + excess.limit() + " units is " + units + " short at " + at + "."
                : "The line exceeds the Team's Quota of " + excess.limit() + " " + type + " units by " + units
                        + " at " + at + ".";
    }

    private List<MeteredAllocationRow> rowsOf(LineSnapshot line, Set<RowState> states) {
        return allocations.findByLineIdAndStateIn(line.lineId().value(), states);
    }

    private static void checkClass(LineSnapshot line) {
        if (line.allocationClass() != AllocationClass.METERED) {
            throw new IllegalArgumentException("Line " + line.lineId().value() + " is not Metered.");
        }
    }

    // ---------------------------------------------------------------- tier Bumps

    /**
     * The lines of the type with live rows overlapping the window, each with all its live rows; then
     * {@link TierBumpSearch} chooses, with the cutoff at now plus the type's bump cutoff. Empty for
     * a line without a tier, a line that fits, and a type without a bump cutoff.
     */
    @Override
    public Optional<BumpPlan> planBump(LineSnapshot line) {
        checkClass(line);
        Optional<Duration> bumpCutoff = settings.current().pricingOf(line.typeId())
                .map(TypePricing::bumpCutoff);
        if (line.tierPremium() == null || bumpCutoff.isEmpty() || excess(line).isEmpty()) {
            return Optional.empty();
        }
        TimeWindow window = line.window();
        Map<Long, List<TierBumpSearch.Span>> spans = new LinkedHashMap<>();
        for (MeteredAllocationRow row : allocations.overlapping(line.typeId().value(), RowState.LIVE, window.start(),
                window.end())) {
            spans.computeIfAbsent(row.lineId(), id -> new ArrayList<>())
                    .add(new TierBumpSearch.Span(row.units(), row.window().start(), row.window().end()));
        }
        List<TierBumpSearch.Candidate> candidates = spans.entrySet().stream()
                .map(entry -> candidate(entry.getKey(), entry.getValue()))
                .toList();
        List<Hour> hours = sums.forWindow(line.typeId(), line.teamId(), window);
        Instant cutoff = clock.instant().plus(bumpCutoff.get());
        return TierBumpSearch.choose(line.units(), line.tierPremium(), line.requestId().value(),
                        line.teamId().value(), hours, inventory.poolCapacity(line.typeId()),
                        settings.current().quotaOf(line.teamId(), line.typeId()), candidates, cutoff)
                .map(chosen -> new BumpPlan(line, List.of(), chosen.stream()
                        .map(bumped -> new BumpPlan.BumpedLine(new RequestLineId(bumped.lineId()),
                                new RequestId(bumped.requestId()), new TeamId(bumped.teamId()), bumped.held()))
                        .toList()));
    }

    /**
     * Plans again and requires the same plan; then marks every live row of the Bumped lines BUMPED
     * and flushes before the tiered line's row is inserted as HELD, so the hourly sums no longer
     * count them.
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
            allocations.findByLineIdAndStateIn(bumped.line().value(), RowState.LIVE)
                    .forEach(row -> row.changeState(RowState.BUMPED));
        }
        allocations.flush();
        insert(line, RowState.HELD);
    }

    /** A line with live rows in the window, as the plan sees it, from all its live rows. */
    private TierBumpSearch.Candidate candidate(Long lineId, List<TierBumpSearch.Span> inWindow) {
        List<MeteredAllocationRow> rows = allocations.findByLineIdAndStateIn(lineId, RowState.LIVE);
        MeteredAllocationRow first = rows.stream()
                .min(Comparator.comparing((MeteredAllocationRow row) -> row.window().start()))
                .orElseThrow();
        return new TierBumpSearch.Candidate(lineId, first.requestId(), first.teamId(), first.tierPremium(),
                first.submittedAt(), first.window().start(),
                rows.stream().allMatch(row -> row.state() == RowState.HELD), inWindow);
    }
}
