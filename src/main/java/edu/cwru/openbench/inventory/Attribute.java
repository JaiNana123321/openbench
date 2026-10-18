package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.AttributeId;
import java.util.List;

/**
 * An attribute of a Resource Type: a name, a kind, and for LIST the allowed values in order (null
 * for every other kind). In a {@link ResourceTypeDefinition} the id is null for a new attribute and
 * names the attribute to keep, rename, or retype for an existing one.
 */
public record Attribute(AttributeId id, String name, AttributeKind kind, List<String> allowedValues) {}
