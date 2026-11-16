package edu.cwru.openbench.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies a Metered allocation: units of a pool held by a line over a window. JSON: a bare number. */
public record MeteredAllocationId(@JsonValue long value) implements TypedId {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public MeteredAllocationId {}
}
