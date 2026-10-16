package edu.cwru.openbench.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies a Request. JSON: a bare number. */
public record RequestId(@JsonValue long value) implements TypedId {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public RequestId {}
}
