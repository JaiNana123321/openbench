package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.ResourceTypeId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * What a Resource Owner gives to add a Resource. {@code units} is required for a Metered type and
 * may be left out (or be 1) for an Exclusive one. {@code attributes} maps attribute names to values
 * as text; a blank value leaves the attribute without one.
 */
public record NewResource(
        @NotNull ResourceTypeId typeId,
        @NotBlank String name,
        Integer units,
        Map<String, String> attributes) {

    public NewResource {
        attributes = attributes == null ? Map.of() : attributes;
    }
}
