package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.inventory.ReductionGuard;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY: the Allocation agent (brief 06) replaces this class with the real guard, which sums
 * future allocated and held units per hour. Until Allocation writes rows, no Resource has any usage,
 * so "no failing hour" is the correct answer to every reduction.
 */
@Component
class NoUsageReductionGuard implements ReductionGuard {

    @Override
    public Optional<Instant> earliestHourExceeding(
            ResourceTypeId type, Optional<ResourceId> instance, int newUnits, Instant from) {
        return Optional.empty();
    }
}
