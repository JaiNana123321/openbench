package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import java.time.Instant;
import java.util.Optional;

/**
 * The port through which Inventory learns future usage without depending on the layer above:
 * declared here, implemented by Allocation, and injected by Spring (design Section 4.2).
 * {@link ResourceService} calls it while holding the type's row lock, before it writes.
 */
public interface ReductionGuard {

    /**
     * The first hour at or after {@code from} at which the change would leave too little capacity,
     * or empty when no hour fails.
     *
     * @param type the Resource's type
     * @param instance the Resource whose units change or that retires
     * @param newUnits the Resource's units after the change; 0 for a retirement. For a Metered type
     *     the guard compares allocated plus held units in each hour against the Pool Capacity that
     *     would result; for an Exclusive instance it returns the start of its first future Held or
     *     Allocated occurrence.
     * @param from the current instant
     */
    Optional<Instant> earliestHourExceeding(
            ResourceTypeId type, Optional<ResourceId> instance, int newUnits, Instant from);
}
