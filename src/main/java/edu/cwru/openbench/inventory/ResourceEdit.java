package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.MemberId;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * What a Resource's owner gives to edit it: the name and every attribute value (an attribute left
 * out or blank loses its value), and optionally a new owner, who must be an active Resource Owner.
 * Units change through {@code setUnits}, which checks future usage.
 */
public record ResourceEdit(@NotBlank String name, Map<String, String> attributes, MemberId ownerId) {

    public ResourceEdit {
        attributes = attributes == null ? Map.of() : attributes;
    }
}
