package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.ResourceTypeId;
import java.util.List;

/**
 * A Resource Type as other modules and the API see it. {@code poolCapacity} is the sum of the units
 * of the type's unretired Resources for a Metered type, computed on every read, and null otherwise.
 */
public record ResourceType(
        ResourceTypeId id,
        String name,
        AllocationClass allocationClass,
        List<Attribute> attributes,
        boolean retired,
        Integer poolCapacity) {

    public ResourceType {
        attributes = List.copyOf(attributes);
    }
}
