package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The read side of Allocation; unscoped, for other modules, which check roles themselves. */
@Component
@Transactional(readOnly = true)
class LiveRowQueries implements AllocationQueries {

    private final WaitlistEntryRepository waitlist;
    private final ExclusiveOccurrenceRepository occurrences;
    private final HourlySums sums;
    private final MeteredAllocationRepository metered;
    private final ExclusiveAllocator exclusiveAllocator;
    private final MeteredAllocator meteredAllocator;

    LiveRowQueries(WaitlistEntryRepository waitlist, ExclusiveOccurrenceRepository occurrences, HourlySums sums,
            MeteredAllocationRepository metered, ExclusiveAllocator exclusiveAllocator,
            MeteredAllocator meteredAllocator) {
        this.waitlist = waitlist;
        this.occurrences = occurrences;
        this.sums = sums;
        this.metered = metered;
        this.exclusiveAllocator = exclusiveAllocator;
        this.meteredAllocator = meteredAllocator;
    }

    @Override
    public Optional<Integer> waitlistPosition(RequestLineId line) {
        return Optional.ofNullable(waitlist.positionOf(line.value()));
    }

    @Override
    public List<ResourceId> resourcesOf(RequestLineId line) {
        return occurrences.resourcesOfLine(line.value(), RowState.LIVE).stream().map(ResourceId::new).toList();
    }

    @Override
    public int peakUnitsInUse(ResourceTypeId type, Optional<ResourceId> instance, Instant from) {
        return HourlyLoad.peak(sums.hoursFrom(type, instance, from));
    }

    @Override
    public Released previewRelease(LineSnapshot line, ReleasePart part) {
        return switch (line.allocationClass()) {
            case EXCLUSIVE -> exclusiveAllocator.preview(line, part);
            case METERED -> meteredAllocator.preview(line, part);
            case MATCHED -> throw new IllegalArgumentException("A Matched line is released by ending its Loan early.");
        };
    }

    @Override
    public List<AllocatedPart> allocatedParts(RequestLineId line) {
        Map<TimeWindow, Integer> parts = new TreeMap<>(Comparator.comparing(TimeWindow::start)
                .thenComparing(TimeWindow::end));
        occurrences.findByLineIdAndStateIn(line.value(), Set.of(RowState.ALLOCATED))
                .forEach(row -> parts.merge(row.window(), 1, Integer::sum));
        metered.findByLineIdAndStateIn(line.value(), Set.of(RowState.ALLOCATED))
                .forEach(row -> parts.merge(row.window(), row.units(), Integer::sum));
        return parts.entrySet().stream().map(part -> new AllocatedPart(part.getKey(), part.getValue())).toList();
    }
}
