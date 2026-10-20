package edu.cwru.openbench.allocation;

import edu.cwru.openbench.allocation.HourlyLoad.Excess;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.settings.SettingsService;
import java.time.Clock;
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
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class MeteredAllocator implements Allocator, Waitlistable {

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
    public void release(LineSnapshot line, ReleasePart part) {
        checkClass(line);
        List<MeteredAllocationRow> allocated = rowsOf(line, Set.of(RowState.ALLOCATED));
        if (allocated.isEmpty()) {
            throw new RuleViolation("NOT_ALLOCATED", "This line holds no allocated units of "
                    + inventory.type(line.typeId()).name() + ".");
        }
        switch (part) {
            case ReleasePart.WholeLine whole -> allocated.forEach(row -> row.changeState(RowState.RELEASED));
        }
        allocations.flush();
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
                line.window().end(), state));
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
}
