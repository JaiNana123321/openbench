package edu.cwru.openbench.inventory;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.members.MemberService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns rows into the module's DTOs, reading a whole list's attributes, values, and owners in a
 * few queries. Shared by the services; the attribute storage stays private to this package.
 */
@Component
class InventoryViews {

    private final ResourceTypeRepository types;
    private final ResourceTypeAttributeRepository attributes;
    private final ResourceRepository resources;
    private final ResourceAttributeValueRepository values;
    private final MemberService members;

    InventoryViews(ResourceTypeRepository types, ResourceTypeAttributeRepository attributes,
            ResourceRepository resources, ResourceAttributeValueRepository values, MemberService members) {
        this.types = types;
        this.attributes = attributes;
        this.resources = resources;
        this.values = values;
        this.members = members;
    }

    ResourceType type(ResourceTypeRow row) {
        return types(List.of(row)).getFirst();
    }

    List<ResourceType> types(List<ResourceTypeRow> rows) {
        Map<Long, List<ResourceTypeAttributeRow>> byType = attributesOf(rows.stream().map(ResourceTypeRow::id).toList());
        return rows.stream()
                .map(row -> new ResourceType(
                        new ResourceTypeId(row.id()),
                        row.name(),
                        row.allocationClass(),
                        byType.getOrDefault(row.id(), List.of()).stream().map(ResourceTypeAttributeRow::toAttribute).toList(),
                        row.retired(),
                        row.allocationClass() == AllocationClass.METERED ? poolCapacity(row.id()) : null))
                .toList();
    }

    int poolCapacity(Long typeId) {
        return Math.toIntExact(resources.sumOfUnits(typeId));
    }

    Resource resource(ResourceRow row) {
        return resources(List.of(row)).getFirst();
    }

    List<Resource> resources(List<ResourceRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> typeIds = rows.stream().map(ResourceRow::typeId).collect(Collectors.toSet());
        Map<Long, String> typeNames = types.findAllById(typeIds).stream()
                .collect(toMap(ResourceTypeRow::id, ResourceTypeRow::name));
        Map<Long, List<ResourceTypeAttributeRow>> attributesByType = attributesOf(typeIds);
        Map<Long, Map<Long, String>> valuesByResource = values.findByResourceIdIn(rows.stream().map(ResourceRow::id).toList())
                .stream()
                .collect(groupingBy(ResourceAttributeValueRow::resourceId,
                        toMap(ResourceAttributeValueRow::attributeId, ResourceAttributeValueRow::value)));
        Map<Long, String> ownerNames = rows.stream().map(ResourceRow::ownerMemberId).distinct()
                .collect(toMap(Function.identity(), owner -> members.summary(new MemberId(owner)).name()));

        return rows.stream().map(row -> {
            Map<Long, String> given = valuesByResource.getOrDefault(row.id(), Map.of());
            Map<String, String> named = new LinkedHashMap<>();
            for (ResourceTypeAttributeRow attribute : attributesByType.getOrDefault(row.typeId(), List.of())) {
                String value = given.get(attribute.id());
                if (value != null) {
                    named.put(attribute.name(), value);
                }
            }
            return new Resource(new ResourceId(row.id()), new ResourceTypeId(row.typeId()), typeNames.get(row.typeId()),
                    row.name(), new MemberId(row.ownerMemberId()), ownerNames.get(row.ownerMemberId()), row.units(),
                    named, row.retired());
        }).toList();
    }

    /** Each type's attributes in the order they were defined. */
    private Map<Long, List<ResourceTypeAttributeRow>> attributesOf(Collection<Long> typeIds) {
        return attributes.findByTypeIdInOrderById(typeIds).stream()
                .collect(groupingBy(ResourceTypeAttributeRow::typeId));
    }
}
