package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.inventory.Resource;
import edu.cwru.openbench.inventory.ResourceFilter;
import edu.cwru.openbench.inventory.ResourceType;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The holder views of the Inventory (SRS Inventory.View.Holder and the free-for-a-window part of
 * Inventory.View.Filter; design Section 7: {@code GET /inventory/resources/{id}/holders} and
 * {@code /inventory/resources/free}, served by Allocation, which owns the rows). Read-only: it takes
 * no lock.
 *
 * <p>Only an Exclusive Resource has a timeline: a Metered type's units come from its pool, and the
 * design describes no per-Resource view of them ({@code CLASS_MISMATCH}). A window is half-open, on
 * whole hours, and at most {@value #LONGEST_WINDOW_DAYS} days long ({@code WINDOW_INVALID}); it may lie
 * in the past.
 *
 * <p>Scope (design Table "What each role sees"): every Member sees whether each part is free, on
 * Hold, or allocated. The holding Team and Request are shown to a Capacity Planner, to the Resource's
 * owner, and to a Manager for their own Team's parts; anyone else gets the part without its holder.
 */
@Service
@Transactional(readOnly = true)
public class HolderService {

    static final int LONGEST_WINDOW_DAYS = 31;

    private final AllocationQueries queries;
    private final InventoryQueries inventory;
    private final ExclusiveOccurrenceRepository occurrences;

    HolderService(AllocationQueries queries, InventoryQueries inventory, ExclusiveOccurrenceRepository occurrences) {
        this.queries = queries;
        this.inventory = inventory;
        this.occurrences = occurrences;
    }

    /**
     * GET /api/inventory/resources/{id}/holders: the Resource's timeline over the window, as ordered
     * parts that cover it exactly, with each holder the caller may see.
     *
     * @throws NotFoundException for an unknown Resource
     * @throws RuleViolation {@code WINDOW_INVALID} for a window that does not end after it starts, is
     *     not on whole hours, or is too long; {@code CLASS_MISMATCH} for a Resource that is not Exclusive
     */
    @PreAuthorize("hasAuthority('MEMBER')")
    public List<Holding> holders(Actor actor, ResourceId id, Instant from, Instant to) {
        Resource resource = inventory.resource(id);
        checkExclusive(inventory.type(resource.typeId()));
        TimeWindow window = window(from, to);
        boolean seesEveryHolder = actor.has(Role.PLANNER)
                || (actor.has(Role.RESOURCE_OWNER) && resource.ownerId().equals(actor.memberId()));
        return queries.holders(id, window).stream()
                .map(part -> part.state() == HoldingState.FREE || seesEveryHolder || ownTeamsPart(actor, part)
                        ? part
                        : part.withoutHolder())
                .toList();
    }

    /**
     * GET /api/inventory/resources/free: one page, by name, of the unretired Resources of the type
     * that match the attribute filter and have no live occurrence overlapping the window. A Resource
     * whose live occurrence ends when the window starts, or starts when it ends, is free.
     *
     * @throws NotFoundException for an unknown Resource Type
     * @throws RuleViolation {@code WINDOW_INVALID} as for {@link #holders}; {@code CLASS_MISMATCH} for a
     *     type that is not Exclusive
     */
    @PreAuthorize("hasAuthority('MEMBER')")
    public Page<Resource> free(ResourceFilter filter, Instant from, Instant to, int page, int size) {
        Objects.requireNonNull(filter.type(), "type");
        checkExclusive(inventory.type(filter.type()));
        TimeWindow window = window(from, to);
        List<Resource> matching = inventory.search(new ResourceFilter(filter.type(), filter.attributes(), false));
        Set<Long> busy = matching.isEmpty() ? Set.of() : new HashSet<>(occurrences.busyAmong(
                matching.stream().map(resource -> resource.id().value()).toList(),
                RowState.LIVE, window.start(), window.end()));
        List<Resource> free = matching.stream().filter(resource -> !busy.contains(resource.id().value())).toList();
        int first = (int) Math.min((long) page * size, free.size());
        int last = Math.min(first + size, free.size());
        return new PageImpl<>(free.subList(first, last), PageRequest.of(page, size), free.size());
    }

    private static boolean ownTeamsPart(Actor actor, Holding part) {
        return actor.has(Role.MANAGER) && part.team() != null && part.team().id().equals(actor.teamId());
    }

    private static void checkExclusive(ResourceType type) {
        if (type.allocationClass() != AllocationClass.EXCLUSIVE) {
            throw new RuleViolation("CLASS_MISMATCH", type.name() + " is " + type.allocationClass().name()
                    .toLowerCase() + ": only an Exclusive Resource has a timeline of free, held, and allocated parts.");
        }
    }

    private static TimeWindow window(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new RuleViolation("WINDOW_INVALID", "The window must end after it starts.");
        }
        TimeWindow window = new TimeWindow(from, to);
        if (!Windows.onWholeHours(window)) {
            throw new RuleViolation("WINDOW_INVALID", "The window must start and end on a whole hour.");
        }
        if (window.duration().compareTo(Duration.ofDays(LONGEST_WINDOW_DAYS)) > 0) {
            throw new RuleViolation("WINDOW_INVALID",
                    "The window may be at most " + LONGEST_WINDOW_DAYS + " days long.");
        }
        return window;
    }
}
