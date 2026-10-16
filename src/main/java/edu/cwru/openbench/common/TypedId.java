package edu.cwru.openbench.common;

import java.io.Serializable;

/**
 * A typed wrapper around a table's {@code bigint} key, such as {@link TeamId} or {@link RequestId}.
 *
 * <p>Each id is a one-component record that serializes to and from JSON as a bare number
 * ({@code @JsonValue} and a delegating {@code @JsonCreator} on the record) and converts from a path
 * variable or request parameter through the converter registered in {@code config}. Entities keep
 * plain {@code Long} keys; services convert at their boundary, so typed ids appear only in the
 * public surface of a module.
 */
public interface TypedId extends Serializable {

    long value();
}
