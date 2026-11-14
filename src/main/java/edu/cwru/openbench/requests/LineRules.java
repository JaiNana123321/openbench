package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.AllocatorRegistry;
import edu.cwru.openbench.allocation.Bumpable;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.WeeklyRecurrence;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.inventory.Resource;
import edu.cwru.openbench.inventory.ResourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * The rules every added or edited line obeys (design Section 4.8, Table "Requests operations"; SRS
 * Draft.Line.Validate), checked again at submission:
 *
 * <ul>
 * <li>the type exists ({@code TYPE_UNKNOWN}), is not retired ({@code TYPE_RETIRED}), and is Exclusive
 *     or Metered ({@code CLASS_NOT_OFFERED}): Person lines are checked by {@link PersonLineRules};
 * <li>the window ends after it starts and both ends fall on whole hours of the company time zone
 *     ({@code WINDOW_INVALID}), and it starts after now ({@code WINDOW_NOT_FUTURE});
 * <li>the quantity is at least 1 ({@code QUANTITY_INVALID});
 * <li>a named Resource exists ({@code RESOURCE_UNKNOWN}), only on an Exclusive line
 *     ({@code NAMED_RESOURCE_NOT_OFFERED}), is of the line's type ({@code TYPE_MISMATCH}), is not
 *     retired ({@code RESOURCE_RETIRED}), and makes the quantity 1 ({@code QUANTITY_INVALID});
 * <li>a weekly recurrence only on an Exclusive line ({@code RECURRENCE_NOT_OFFERED}), with 2 to 52
 *     occurrences and a window of at most one week, so that its occurrences never overlap each other
 *     ({@code RECURRENCE_INVALID});
 * <li>the Priority mark only on an Exclusive line ({@code PRIORITY_NOT_OFFERED}).
 * </ul>
 */
@Component
class LineRules {

    private final InventoryQueries inventory;
    private final Clock clock;
    private final AllocatorRegistry allocators;

    LineRules(InventoryQueries inventory, Clock clock, AllocatorRegistry allocators) {
        this.inventory = inventory;
        this.clock = clock;
        this.allocators = allocators;
    }

    /** Checks a line and returns its Resource Type. */
    ResourceType check(ResourceTypeId typeId, int quantity, Instant start, Instant end, ResourceId namedResource) {
        ResourceType type = typeOf(typeId);
        if (type.retired()) {
            throw new RuleViolation("TYPE_RETIRED", "The Resource Type " + type.name() + " is retired.");
        }
        if (type.allocationClass() == AllocationClass.MATCHED) {
            throw new RuleViolation("CLASS_NOT_OFFERED",
                    "Lines for People (" + type.name() + ") cannot be requested yet; choose an Exclusive or Metered type.");
        }
        checkWindow(start, end);
        if (quantity < 1) {
            throw new RuleViolation("QUANTITY_INVALID", "A line asks for at least 1 "
                    + (type.allocationClass() == AllocationClass.METERED ? "unit." : "instance."));
        }
        if (namedResource != null) {
            checkNamedResource(type, quantity, namedResource);
        }
        return type;
    }

    private ResourceType typeOf(ResourceTypeId id) {
        try {
            return inventory.type(id);
        } catch (NotFoundException e) {
            throw new RuleViolation("TYPE_UNKNOWN", "Resource Type " + id.value() + " does not exist.");
        }
    }

    private void checkWindow(Instant start, Instant end) {
        if (!start.isBefore(end)) {
            throw new RuleViolation("WINDOW_INVALID", "The window must end after it starts.");
        }
        if (!isWholeHour(start) || !isWholeHour(end)) {
            throw new RuleViolation("WINDOW_INVALID", "The window must start and end on a whole hour.");
        }
        if (!start.isAfter(clock.instant())) {
            throw new RuleViolation("WINDOW_NOT_FUTURE", "The window must start in the future.");
        }
    }

    private boolean isWholeHour(Instant instant) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, clock.getZone());
        return local.getMinute() == 0 && local.getSecond() == 0 && local.getNano() == 0;
    }

    private void checkNamedResource(ResourceType type, int quantity, ResourceId id) {
        if (type.allocationClass() != AllocationClass.EXCLUSIVE) {
            throw new RuleViolation("NAMED_RESOURCE_NOT_OFFERED",
                    "A " + type.name() + " line asks for units and cannot name a Resource.");
        }
        Resource resource;
        try {
            resource = inventory.resource(id);
        } catch (NotFoundException e) {
            throw new RuleViolation("RESOURCE_UNKNOWN", "Resource " + id.value() + " does not exist.");
        }
        if (!resource.typeId().equals(type.id())) {
            throw new RuleViolation("TYPE_MISMATCH",
                    resource.name() + " is a " + resource.typeName() + ", not a " + type.name() + ".");
        }
        if (resource.retired()) {
            throw new RuleViolation("RESOURCE_RETIRED", resource.name() + " is retired.");
        }
        if (quantity != 1) {
            throw new RuleViolation("QUANTITY_INVALID", "A line that names " + resource.name() + " asks for 1 instance.");
        }
    }

    /** Checks a line's weekly recurrence, if it has one; call after {@link #check} for the same line. */
    void checkRecurrence(ResourceTypeId typeId, Instant start, Instant end, WeeklyRecurrence recurrence) {
        if (recurrence == null) {
            return;
        }
        ResourceType type = typeOf(typeId);
        if (type.allocationClass() != AllocationClass.EXCLUSIVE) {
            throw new RuleViolation("RECURRENCE_NOT_OFFERED",
                    "A " + type.name() + " line cannot recur; only lines for Exclusive resources recur weekly.");
        }
        int occurrences = recurrence.occurrences();
        if (occurrences < WeeklyRecurrence.MIN_OCCURRENCES || occurrences > WeeklyRecurrence.MAX_OCCURRENCES) {
            throw new RuleViolation("RECURRENCE_INVALID", "A weekly line has " + WeeklyRecurrence.MIN_OCCURRENCES
                    + " to " + WeeklyRecurrence.MAX_OCCURRENCES + " occurrences.");
        }
        Instant secondStart = LocalDateTime.ofInstant(start, clock.getZone()).plusWeeks(1)
                .atZone(clock.getZone()).toInstant();
        if (end.isAfter(secondStart)) {
            throw new RuleViolation("RECURRENCE_INVALID",
                    "A weekly line's window lasts at most one week, so that its occurrences do not overlap.");
        }
    }

    /**
     * Checks a line's Priority mark: offered only for a class whose allocator is {@link Bumpable}
     * (design Section 4.5), which is the Exclusive class.
     */
    void checkPriority(ResourceTypeId typeId, boolean priority) {
        if (!priority) {
            return;
        }
        ResourceType type = typeOf(typeId);
        if (allocators.capability(type.allocationClass(), Bumpable.class).isEmpty()) {
            throw new RuleViolation("PRIORITY_NOT_OFFERED",
                    "A " + type.name() + " line cannot be marked Priority; Priority is offered for Exclusive lines only.");
        }
    }
}
