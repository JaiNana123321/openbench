package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * @param occurrences every occurrence of the line, by start: the window alone, or one window per week
 *     of a weekly recurrence (only an Exclusive line recurs), expanded by Requests in the company time
 *     zone; the first is {@code window}
 * @param namedMember the one Person a Matched line names, if any; then the quantity is 1
 * @param skills the skills a skill-based Matched line asks for; empty otherwise. A Matched line's
 *     window runs from midnight of its first day to midnight after its last, company time
 * @param tierPremium the premium of a Metered line's service tier, locked at submission, which ranks
 *     it for Bumps (SRS Metered.Tier.Bump); null for a line without a tier, which ranks below every tier
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
        boolean bumped,
        List<TimeWindow> occurrences,
        Optional<MemberId> namedMember,
        Set<SkillId> skills,
        BigDecimal tierPremium) {

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
        occurrences = occurrences == null ? List.of(window) : List.copyOf(occurrences);
        if (occurrences.isEmpty() || !occurrences.getFirst().equals(window)) {
            throw new IllegalArgumentException("A line's first occurrence is its window.");
        }
        if (occurrences.size() > 1 && allocationClass != AllocationClass.EXCLUSIVE) {
            throw new IllegalArgumentException("Only an Exclusive line recurs.");
        }
        Objects.requireNonNull(namedMember, "namedMember");
        skills = Set.copyOf(skills);
        if (namedMember.isPresent() && (allocationClass != AllocationClass.MATCHED || quantity != 1)) {
            throw new IllegalArgumentException("Only a Matched line of quantity 1 names a Person.");
        }
        if (tierPremium != null && allocationClass != AllocationClass.METERED) {
            throw new IllegalArgumentException("Only a Metered line has a service tier.");
        }
    }

    /** An Exclusive or Metered line with one occurrence, its window: no recurrence, no named Person, no skills, no tier. */
    public LineSnapshot(RequestLineId lineId, RequestId requestId, TeamId teamId, ResourceTypeId typeId,
            AllocationClass allocationClass, int quantity, Integer units, TimeWindow window,
            Optional<ResourceId> namedResource, boolean required, boolean priority, Instant submittedAt,
            boolean bumped) {
        this(lineId, requestId, teamId, typeId, allocationClass, quantity, units, window, namedResource, required,
                priority, submittedAt, bumped, null, Optional.empty(), Set.of(), null);
    }

    /** From the start of the first occurrence to the end of the last: the window itself without a recurrence. */
    public TimeWindow span() {
        return new TimeWindow(window.start(), occurrences.getLast().end());
    }
}
