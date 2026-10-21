package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable copy of a submitted Request Line, built by Requests: the only thing Allocation knows
 * about a line (design Section 4.5). Allocation never reads {@code request} or {@code request_line}.
 *
 * @param quantity the instances an Exclusive line asks for (1 or more); 1 for a Metered line
 * @param units the units a Metered line holds concurrently over its whole window; null otherwise
 * @param window the half-open window, on whole hours
 * @param namedResource the Resource an Exclusive line names, if any; then it is the only candidate
 * @param submittedAt the Request's submission time, which orders the waitlist
 * @param bumped whether the line lost its occurrences to a Priority line, which puts it at the head
 *     of the waitlist
 */
public record LineSnapshot(
        RequestLineId lineId,
        RequestId requestId,
        TeamId teamId,
        ResourceTypeId typeId,
        AllocationClass allocationClass,
        int quantity,
        Integer units,
        TimeWindow window,
        Optional<ResourceId> namedResource,
        boolean required,
        boolean priority,
        Instant submittedAt,
        boolean bumped) {

    public LineSnapshot {
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(allocationClass, "allocationClass");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(namedResource, "namedResource");
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (quantity < 1) {
            throw new IllegalArgumentException("A line asks for at least one instance.");
        }
        if (allocationClass == AllocationClass.METERED && (units == null || units < 1)) {
            throw new IllegalArgumentException("A Metered line asks for at least one unit.");
        }
        if (namedResource.isPresent() && quantity != 1) {
            throw new IllegalArgumentException("A line that names a Resource asks for exactly that one instance.");
        }
    }
}
