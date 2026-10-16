package edu.cwru.openbench.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies a Member. JSON: a bare number. */
public record MemberId(@JsonValue long value) implements TypedId {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public MemberId {}
}
