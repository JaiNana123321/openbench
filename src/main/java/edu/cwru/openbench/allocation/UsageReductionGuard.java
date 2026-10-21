package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.inventory.ReductionGuard;
import edu.cwru.openbench.inventory.Resource;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory's reduction guard (requirement Inventory.Resource.Add, design Section 4.5): locks the
 * type, computes the capacity the change would leave, and if the peak of live units from now on
 * exceeds it, names the earliest hour that would fail. The lock lasts until Inventory commits, so
 * no Hold or allocation slips in between the check and the change.
 *
 * <p>For a Metered Resource the capacity left is the Pool Capacity less the Resource's current units
 * plus its new ones. For an Exclusive instance it is the instance's own units after the change, so
 * a retirement (0) fails at its first live occurrence from now on.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class UsageReductionGuard implements ReductionGuard {

    private final AllocationLock lock;
    private final AllocationQueries queries;
    private final HourlySums sums;
    private final InventoryQueries inventory;

    UsageReductionGuard(AllocationLock lock, AllocationQueries queries, HourlySums sums, InventoryQueries inventory) {
        this.lock = lock;
        this.queries = queries;
        this.sums = sums;
        this.inventory = inventory;
    }

    @Override
    public Optional<Instant> earliestHourExceeding(
            ResourceTypeId type, Optional<ResourceId> instance, int newUnits, Instant from) {
        lock.lockTypes(new TreeSet<>(Set.of(type)));
        AllocationClass allocationClass = inventory.type(type).allocationClass();
        int capacityLeft = switch (allocationClass) {
            case METERED -> inventory.poolCapacity(type) - currentUnits(instance) + newUnits;
            case EXCLUSIVE -> newUnits;
            case MATCHED -> Integer.MAX_VALUE;
        };
        if (queries.peakUnitsInUse(type, instance, from) <= capacityLeft) {
            return Optional.empty();
        }
        return HourlyLoad.firstAbove(sums.hoursFrom(type, instance, from), capacityLeft);
    }

    /** What the Resource adds to the Pool Capacity now: its units, or 0 once retired. */
    private int currentUnits(Optional<ResourceId> instance) {
        return instance.map(inventory::resource).filter(resource -> !resource.retired()).map(Resource::units).orElse(0);
    }
}
