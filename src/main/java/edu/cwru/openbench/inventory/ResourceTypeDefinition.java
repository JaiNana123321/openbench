package edu.cwru.openbench.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an Administrator gives to define or change a Resource Type: a name, one Allocation Class,
 * and the complete list of attributes. On a change, an existing attribute left out is removed.
 */
public record ResourceTypeDefinition(
        @NotBlank String name,
        @NotNull AllocationClass allocationClass,
        List<Attribute> attributes) {

    public ResourceTypeDefinition {
        attributes = attributes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(attributes));
    }
}
