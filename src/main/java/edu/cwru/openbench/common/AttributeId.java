package edu.cwru.openbench.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies an attribute definition of a Resource Type. JSON: a bare number. */
public record AttributeId(@JsonValue long value) implements TypedId {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public AttributeId {}
}
