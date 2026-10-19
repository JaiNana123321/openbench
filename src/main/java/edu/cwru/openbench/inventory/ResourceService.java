package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ForbiddenException;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.MemberSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Resource Owner adds Exclusive and Metered Resources and keeps them current (requirement
 * Inventory.Resource.Add). Only a Resource's owner changes it, and nothing is deleted: a Resource is
 * only ever retired. A reduction or retirement is refused while future usage needs the capacity.
 */
@Service
@Transactional(readOnly = true)
public class ResourceService {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("EEE d MMM uuuu, HH:mm", Locale.US);

    private final ResourceTypeRepository types;
    private final ResourceTypeAttributeRepository attributes;
    private final ResourceRepository resources;
    private final ResourceAttributeValueRepository values;
    private final InventoryViews views;
    private final MemberService members;
    private final ReductionGuard guard;
    private final Clock clock;

    ResourceService(ResourceTypeRepository types, ResourceTypeAttributeRepository attributes,
            ResourceRepository resources, ResourceAttributeValueRepository values, InventoryViews views,
            MemberService members, ReductionGuard guard, Clock clock) {
        this.types = types;
        this.attributes = attributes;
        this.resources = resources;
        this.values = values;
        this.views = views;
        this.members = members;
        this.guard = guard;
        this.clock = clock;
    }

    /** A new Resource of an Exclusive or Metered type that is not retired; the caller becomes its owner. */
    @PreAuthorize("hasAuthority('RESOURCE_OWNER')")
    @Transactional
    public Resource add(Actor actor, NewResource command) {
        ResourceTypeRow type = types.findById(command.typeId().value())
                .orElseThrow(() -> InventoryQueries.typeNotFound(command.typeId()));
        if (type.allocationClass() == AllocationClass.MATCHED) {
            throw new RuleViolation("MATCHED_HAS_NO_RESOURCES",
                    "The Resources of " + type.name() + " are Members; it has no Resources to add.");
        }
        if (type.retired()) {
            throw new RuleViolation("TYPE_RETIRED", "The Resource Type " + type.name() + " is retired.");
        }
        int units = unitsOfNew(type, command.units());
        String name = command.name().trim();
        checkNameFree(type, name, 0);
        Map<Long, String> checked = checkedValues(type, command.attributes());

        ResourceRow resource = resources.save(new ResourceRow(type.id(), name, actor.memberId().value(), units));
        writeValues(resource, checked, actor);
        return views.resource(resource);
    }

    /**
     * The owner renames the Resource, replaces its attribute values, and may hand it to another
     * active Resource Owner.
     */
    @PreAuthorize("hasAuthority('RESOURCE_OWNER')")
    @Transactional
    public Resource edit(Actor actor, ResourceId id, ResourceEdit command) {
        ResourceRow resource = ownedByCaller(actor, id);
        ResourceTypeRow type = types.findById(resource.typeId()).orElseThrow();
        String name = command.name().trim();
        checkNameFree(type, name, resource.id());
        Map<Long, String> checked = checkedValues(type, command.attributes());
        Long owner = resource.ownerMemberId();
        if (command.ownerId() != null && command.ownerId().value() != owner) {
            checkResourceOwner(command.ownerId());
            owner = command.ownerId().value();
        }
        resource.edit(name, owner);
        writeValues(resource, checked, actor);
        return views.resource(resource);
    }

    /**
     * The owner changes a Metered Resource's units. A reduction locks the type and asks the guard
     * first, and is refused naming the earliest hour at which usage would exceed the new capacity.
     */
    @PreAuthorize("hasAuthority('RESOURCE_OWNER')")
    @Transactional
    public Resource setUnits(Actor actor, ResourceId id, int units) {
        ResourceRow resource = ownedByCaller(actor, id);
        ResourceTypeRow type = types.findById(resource.typeId()).orElseThrow();
        if (type.allocationClass() != AllocationClass.METERED) {
            throw new RuleViolation("NOT_METERED", resource.name() + " is not Metered: its units are always 1.");
        }
        if (units < 1) {
            throw new RuleViolation("UNITS_INVALID", "A Metered Resource supplies at least 1 unit.");
        }
        if (units < resource.units()) {
            types.lock(type.id());
            guard.earliestHourExceeding(new ResourceTypeId(type.id()), Optional.of(id), units, clock.instant())
                    .ifPresent(hour -> {
                        throw new RuleViolation("UNITS_BELOW_USAGE", "Allocated and held units of " + type.name()
                                + " would exceed the new Pool Capacity from " + format(hour) + ".",
                                Map.of("earliestHour", hour));
                    });
        }
        resource.setUnits(units);
        return views.resource(resource);
    }

    /** The owner retires the Resource; refused while it is in use from now on, naming the first hour. */
    @PreAuthorize("hasAuthority('RESOURCE_OWNER')")
    @Transactional
    public Resource retire(Actor actor, ResourceId id) {
        ResourceRow resource = ownedByCaller(actor, id);
        ResourceTypeRow type = types.findById(resource.typeId()).orElseThrow();
        types.lock(type.id());
        guard.earliestHourExceeding(new ResourceTypeId(type.id()), Optional.of(id), 0, clock.instant())
                .ifPresent(hour -> {
                    throw type.allocationClass() == AllocationClass.EXCLUSIVE
                            ? new RuleViolation("RESOURCE_IN_USE", resource.name() + " is held or allocated from "
                                    + format(hour) + ".", Map.of("earliestHour", hour))
                            : new RuleViolation("UNITS_BELOW_USAGE", "Allocated and held units of " + type.name()
                                    + " would exceed the Pool Capacity left without " + resource.name() + " from "
                                    + format(hour) + ".", Map.of("earliestHour", hour));
                });
        resource.retire(clock.instant());
        return views.resource(resource);
    }

    /** The Resource, if the caller owns it and it is not retired. */
    private ResourceRow ownedByCaller(Actor actor, ResourceId id) {
        ResourceRow resource = resources.findById(id.value())
                .orElseThrow(() -> new NotFoundException("Resource " + id.value() + " does not exist."));
        if (resource.ownerMemberId() != actor.memberId().value()) {
            throw new ForbiddenException("Only the owner of " + resource.name() + " can change it.");
        }
        if (resource.retired()) {
            throw new RuleViolation("WRONG_STATE", resource.name() + " is retired and cannot change.");
        }
        return resource;
    }

    private static int unitsOfNew(ResourceTypeRow type, Integer units) {
        if (type.allocationClass() == AllocationClass.EXCLUSIVE) {
            if (units != null && units != 1) {
                throw new RuleViolation("UNITS_INVALID", "An Exclusive Resource is one instance: its units are 1.");
            }
            return 1;
        }
        if (units == null || units < 1) {
            throw new RuleViolation("UNITS_INVALID", "A Metered Resource supplies at least 1 unit.");
        }
        return units;
    }

    private void checkNameFree(ResourceTypeRow type, String name, long exceptId) {
        if (resources.nameTaken(type.id(), name, exceptId)) {
            throw new RuleViolation("RESOURCE_NAME_TAKEN", "A " + type.name() + " named " + name + " already exists.");
        }
    }

    private void checkResourceOwner(MemberId candidate) {
        MemberSummary member;
        try {
            member = members.summary(candidate);
        } catch (NotFoundException e) {
            throw notAResourceOwner();
        }
        if (!member.active() || !member.has(Role.RESOURCE_OWNER)) {
            throw notAResourceOwner();
        }
    }

    private static RuleViolation notAResourceOwner() {
        return new RuleViolation("NOT_A_RESOURCE_OWNER", "The new owner must be an active Resource Owner.");
    }

    /**
     * The given values by attribute id, trimmed, blank ones left out; ATTRIBUTE_INVALID for a name
     * the type does not have or a value that does not fit the attribute's kind.
     */
    private Map<Long, String> checkedValues(ResourceTypeRow type, Map<String, String> given) {
        Map<String, ResourceTypeAttributeRow> byName = attributes.findByTypeIdOrderById(type.id()).stream()
                .collect(Collectors.toMap(ResourceTypeAttributeRow::name, Function.identity()));
        Map<Long, String> checked = new LinkedHashMap<>();
        given.forEach((name, raw) -> {
            ResourceTypeAttributeRow attribute = byName.get(name);
            if (attribute == null) {
                throw new RuleViolation("ATTRIBUTE_INVALID", type.name() + " has no attribute named " + name + ".");
            }
            if (raw == null || raw.isBlank()) {
                return;
            }
            String value = raw.trim();
            if (!attribute.accepts(value)) {
                throw new RuleViolation("ATTRIBUTE_INVALID", "The attribute " + name + " takes "
                        + expected(attribute) + "; \"" + value + "\" is not one.");
            }
            checked.put(attribute.id(), value);
        });
        return checked;
    }

    private static String expected(ResourceTypeAttributeRow attribute) {
        return switch (attribute.kind()) {
            case TEXT -> "text";
            case NUMBER -> "a number";
            case DATE -> "a date such as 2026-10-27";
            case LIST -> "one of " + String.join(", ", attribute.allowedValues());
        };
    }

    /** Makes the Resource's stored values exactly {@code checked}. */
    private void writeValues(ResourceRow resource, Map<Long, String> checked, Actor actor) {
        List<ResourceAttributeValueRow> stored = values.findByResourceId(resource.id());
        Map<Long, String> missing = new LinkedHashMap<>(checked);
        for (ResourceAttributeValueRow row : stored) {
            String value = missing.remove(row.attributeId());
            if (value == null) {
                values.delete(row);
            } else {
                row.change(value);
            }
        }
        missing.forEach((attributeId, value) ->
                values.save(new ResourceAttributeValueRow(resource.id(), attributeId, value, actor.memberId().value())));
    }

    private String format(Instant hour) {
        return HOUR.format(hour.atZone(clock.getZone()));
    }
}
