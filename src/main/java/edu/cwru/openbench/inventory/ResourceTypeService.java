package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An Administrator defines, changes, and retires Resource Types as data, with no code change
 * (requirement Inventory.Type.Define). Nothing is deleted: a type is only ever retired.
 */
@Service
@Transactional(readOnly = true)
public class ResourceTypeService {

    private final ResourceTypeRepository types;
    private final ResourceTypeAttributeRepository attributes;
    private final ResourceRepository resources;
    private final ResourceAttributeValueRepository values;
    private final InventoryViews views;
    private final Clock clock;

    ResourceTypeService(ResourceTypeRepository types, ResourceTypeAttributeRepository attributes,
            ResourceRepository resources, ResourceAttributeValueRepository values, InventoryViews views, Clock clock) {
        this.types = types;
        this.attributes = attributes;
        this.resources = resources;
        this.values = values;
        this.views = views;
        this.clock = clock;
    }

    /** A new type with a unique name, one Allocation Class, and well-formed attributes. */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public ResourceType define(Actor actor, ResourceTypeDefinition definition) {
        String name = definition.name().trim();
        List<Attribute> wanted = wellFormed(definition.attributes());
        if (types.nameTaken(name, 0)) {
            throw nameTaken(name);
        }
        ResourceTypeRow type = types.save(new ResourceTypeRow(name, definition.allocationClass(), actor.memberId().value()));
        for (Attribute attribute : wanted) {
            attributes.save(new ResourceTypeAttributeRow(
                    type.id(), attribute.name(), attribute.kind(), attribute.allowedValues(), actor.memberId().value()));
        }
        return views.type(type);
    }

    /**
     * Replaces the type's name, class, and attributes. The class is fixed once a Resource of the type
     * exists, and Person keeps its name and class. An existing attribute is matched by id; one left
     * out is removed, and no change may remove an attribute or a value that a Resource uses.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public ResourceType change(Actor actor, ResourceTypeId id, ResourceTypeDefinition definition) {
        ResourceTypeRow type = types.findById(id.value()).orElseThrow(() -> InventoryQueries.typeNotFound(id));
        if (type.retired()) {
            throw new RuleViolation("WRONG_STATE", "The Resource Type " + type.name() + " is retired and cannot change.");
        }
        String name = definition.name().trim();
        if (type.isPerson() && (!name.equals(type.name()) || definition.allocationClass() != AllocationClass.MATCHED)) {
            throw new RuleViolation("PERSON_TYPE_FIXED",
                    "The built-in Person type keeps its name and the Allocation Class Matched.");
        }
        if (definition.allocationClass() != type.allocationClass() && resources.existsByTypeId(type.id())) {
            throw new RuleViolation("CLASS_FIXED",
                    "The Allocation Class of " + type.name() + " cannot change because Resources of it exist.");
        }
        if (types.nameTaken(name, type.id())) {
            throw nameTaken(name);
        }
        List<Attribute> wanted = wellFormed(definition.attributes());

        Map<Long, ResourceTypeAttributeRow> existing = new LinkedHashMap<>();
        attributes.findByTypeIdOrderById(type.id()).forEach(row -> existing.put(row.id(), row));
        Set<Long> kept = new HashSet<>();
        for (Attribute attribute : wanted) {
            if (attribute.id() == null) {
                continue;
            }
            ResourceTypeAttributeRow row = existing.get(attribute.id().value());
            if (row == null || !kept.add(row.id())) {
                throw new RuleViolation("ATTRIBUTE_INVALID",
                        "Attribute " + attribute.id().value() + " is not an attribute of " + type.name() + ".");
            }
            List<String> allowed = attribute.allowedValues() == null ? List.of() : attribute.allowedValues();
            for (String value : values.valuesInUse(row.id())) {
                if (!attribute.kind().accepts(value, allowed)) {
                    throw new RuleViolation("ATTRIBUTE_IN_USE", "A Resource gives the attribute " + row.name()
                            + " the value \"" + value + "\", which the changed attribute would not accept.");
                }
            }
        }
        List<ResourceTypeAttributeRow> removed = existing.values().stream().filter(row -> !kept.contains(row.id())).toList();
        for (ResourceTypeAttributeRow row : removed) {
            if (!values.valuesInUse(row.id()).isEmpty()) {
                throw new RuleViolation("ATTRIBUTE_IN_USE",
                        "The attribute " + row.name() + " cannot be removed because Resources give it a value.");
            }
        }

        // Write in three flushed steps: Hibernate inserts before it deletes, and names are unique per type.
        attributes.deleteAll(removed);
        attributes.flush();
        for (Attribute attribute : wanted) {
            if (attribute.id() != null) {
                existing.get(attribute.id().value()).change(attribute.name(), attribute.kind(), attribute.allowedValues());
            }
        }
        attributes.flush();
        for (Attribute attribute : wanted) {
            if (attribute.id() == null) {
                attributes.save(new ResourceTypeAttributeRow(
                        type.id(), attribute.name(), attribute.kind(), attribute.allowedValues(), actor.memberId().value()));
            }
        }
        type.change(name, definition.allocationClass());
        return views.type(type);
    }

    /** Retires the type: its Resources and submitted lines stay; Requests refuse new lines for it. */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public ResourceType retire(ResourceTypeId id) {
        ResourceTypeRow type = types.findById(id.value()).orElseThrow(() -> InventoryQueries.typeNotFound(id));
        if (type.isPerson()) {
            throw new RuleViolation("PERSON_TYPE_FIXED", "The built-in Person type cannot be retired.");
        }
        if (type.retired()) {
            throw new RuleViolation("WRONG_STATE", "The Resource Type " + type.name() + " is already retired.");
        }
        type.retire(clock.instant());
        return views.type(type);
    }

    /**
     * The attributes trimmed, with null allowed values for every kind but LIST, or ATTRIBUTE_INVALID:
     * a blank or repeated name, a missing kind, a LIST without choices or with a blank or repeated
     * choice, or choices on another kind.
     */
    private static List<Attribute> wellFormed(List<Attribute> attributes) {
        List<Attribute> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Attribute attribute : attributes) {
            if (attribute == null || attribute.name() == null || attribute.name().isBlank()) {
                throw invalid("Every attribute needs a name.");
            }
            String name = attribute.name().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw invalid("The attribute name " + name + " appears more than once.");
            }
            if (attribute.kind() == null) {
                throw invalid("The attribute " + name + " needs a kind: text, number, date, or list.");
            }
            List<String> given = attribute.allowedValues() == null ? List.of() : attribute.allowedValues();
            List<String> allowed = null;
            if (attribute.kind() == AttributeKind.LIST) {
                allowed = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (String value : given) {
                    if (value == null || value.isBlank() || !seen.add(value.trim())) {
                        throw invalid("The allowed values of " + name + " must be distinct and not blank.");
                    }
                    allowed.add(value.trim());
                }
                if (allowed.isEmpty()) {
                    throw invalid("The list attribute " + name + " needs at least one allowed value.");
                }
            } else if (!given.isEmpty()) {
                throw invalid("Only a list attribute has allowed values; " + name + " is " + attribute.kind() + ".");
            }
            result.add(new Attribute(attribute.id(), name, attribute.kind(), allowed));
        }
        return result;
    }

    private static RuleViolation invalid(String message) {
        return new RuleViolation("ATTRIBUTE_INVALID", message);
    }

    private static RuleViolation nameTaken(String name) {
        return new RuleViolation("TYPE_NAME_TAKEN", "A Resource Type named " + name + " already exists.");
    }
}
