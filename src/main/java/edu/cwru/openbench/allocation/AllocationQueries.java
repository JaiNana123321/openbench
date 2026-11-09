package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only questions about live (HELD or ALLOCATED) rows and the waitlist. */
public interface AllocationQueries {

    /**
     * The line's place on its type's waitlist, counting from 1: one more than the number of entries
     * of the same type, with an overlapping window, that come before it in waitlist order (Bumped,
     * then Priority, then submission time, then entry id). Empty when the line is not waiting.
     */
    Optional<Integer> waitlistPosition(RequestLineId line);

    /**
     * The Resources an Exclusive line's live (HELD or ALLOCATED) occurrences are on, by id; empty
     * for a Metered line, whose units come from the pool, and for a line with no live rows.
     */
    List<ResourceId> resourcesOf(RequestLineId line);

    /**
     * The largest hourly sum of live units from {@code from} on, Holds counted: of the type's Metered
     * pool (then {@code instance} is ignored), or for an Exclusive type the live occurrences on
     * {@code instance}, or on all its instances when empty. Hours start on the hour; the first is
     * cut at {@code from}.
     */
    int peakUnitsInUse(ResourceTypeId type, Optional<ResourceId> instance, Instant from);

    /**
     * What {@link Allocator#release} would give back for this part, without writing or locking, so a
     * Manager sees the Refund before confirming.
     *
     * @throws edu.cwru.openbench.common.RuleViolation the codes of {@link Allocator#release}
     */
    Released previewRelease(LineSnapshot line, ReleasePart part);

    /**
     * The line's ALLOCATED parts, one per occurrence by start: its window and its instances
     * (Exclusive) or units (Metered). Empty for a line with nothing allocated.
     */
    List<AllocatedPart> allocatedParts(RequestLineId line);
}
