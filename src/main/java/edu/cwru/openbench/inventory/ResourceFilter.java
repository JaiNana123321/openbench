package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.ResourceTypeId;
import java.util.Map;

/**
 * Which Resources a search returns: of one type when {@code type} is set, giving every listed
 * attribute exactly the listed value, and retired ones only when asked. "Free for a window" is
 * answered by Allocation, not here.
 */
public record ResourceFilter(ResourceTypeId type, Map<String, String> attributes, boolean includeRetired) {

    public ResourceFilter {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Every unretired Resource of the type. */
    public static ResourceFilter ofType(ResourceTypeId type) {
        return new ResourceFilter(type, Map.of(), false);
    }
}
