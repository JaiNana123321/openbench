package edu.cwru.openbench.common;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A business rule refused the action. The {@code code} is a stable identifier the client may branch
 * on, the message one sentence written for the user, and the optional properties extra fields of
 * the problem response (for example {@code shortfall} or {@code earliestHour}).
 *
 * <p>Codes that describe the object's current state map to HTTP 409; every other code maps to 422
 * (design Section 8, Error Taxonomy).
 */
public final class RuleViolation extends RuntimeException {

    private static final Set<String> STATE_CONFLICT_CODES = Set.of(
            "WRONG_STATE",
            "ALREADY_DECIDED",
            "WINDOW_LAPSED",
            "SUGGESTION_CLOSED",
            "NOT_ALLOCATED",
            "PERIOD_STARTED",
            "ALLOCATION_CONFLICT");

    private final String code;
    private final transient Map<String, Object> properties;

    public RuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public RuleViolation(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.properties = Map.copyOf(properties);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    /** True for codes that describe the object's current state (HTTP 409), false otherwise (422). */
    public boolean isStateConflict() {
        return STATE_CONFLICT_CODES.contains(code);
    }
}
