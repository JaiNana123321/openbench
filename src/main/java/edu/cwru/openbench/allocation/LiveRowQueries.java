package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The read side of Allocation; unscoped, for other modules, which check roles themselves. */
@Component
@Transactional(readOnly = true)
class LiveRowQueries implements AllocationQueries {

    private final WaitlistEntryRepository waitlist;
    private final HourlySums sums;

    LiveRowQueries(WaitlistEntryRepository waitlist, HourlySums sums) {
        this.waitlist = waitlist;
        this.sums = sums;
    }

    @Override
    public Optional<Integer> waitlistPosition(RequestLineId line) {
        return Optional.ofNullable(waitlist.positionOf(line.value()));
    }

    @Override
    public int peakUnitsInUse(ResourceTypeId type, Optional<ResourceId> instance, Instant from) {
        return HourlyLoad.peak(sums.hoursFrom(type, instance, from));
    }
}
