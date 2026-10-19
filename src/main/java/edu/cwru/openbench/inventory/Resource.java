package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Resource as other modules and the API see it. {@code attributes} maps attribute names to the
 * values the Resource gives them, in the type's attribute order; an attribute without a value is
 * absent. An Exclusive Resource has units 1.
 */
public record Resource(
        ResourceId id,
        ResourceTypeId typeId,
        String typeName,
        String name,
        MemberId ownerId,
        String ownerName,
        int units,
        Map<String, String> attributes,
        boolean retired) {

    public Resource {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
