package edu.cwru.openbench.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a Resource Type. JSON: a bare number. Ordered by value, the order in which
 * {@code AllocationLock} locks types, so a {@code TreeSet} of them is already in lock order.
 */
public record ResourceTypeId(@JsonValue long value) implements TypedId, Comparable<ResourceTypeId> {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ResourceTypeId {}

    @Override
    public int compareTo(ResourceTypeId other) {
        return Long.compare(value, other.value);
    }
}
