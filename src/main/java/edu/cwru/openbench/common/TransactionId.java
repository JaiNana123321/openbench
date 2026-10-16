package edu.cwru.openbench.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identifies a Transaction in the Ledger. JSON: a bare number. */
public record TransactionId(@JsonValue long value) implements TypedId {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public TransactionId {}
}
