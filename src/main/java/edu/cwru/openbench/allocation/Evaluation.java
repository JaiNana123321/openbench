package edu.cwru.openbench.allocation;

import java.util.Objects;

/**
 * Whether a line fits now. A misfit is a result, never an exception: {@code reason} is a stable code
 * ({@code NO_FREE_INSTANCE}, {@code POOL_FULL}, {@code QUOTA_EXCEEDED}) and {@code message} one
 * sentence for the Manager. Both are null unless the outcome is {@code UNSATISFIABLE}.
 */
public record Evaluation(Outcome outcome, String reason, String message) {

    public enum Outcome {
        /** The line fits now and may be held or committed in this transaction. */
        SATISFIABLE,
        /** The line can fit once approvals arrive (Matched lines, a later increment). */
        NEEDS_APPROVAL,
        /** The line does not fit now; see {@code reason}. */
        UNSATISFIABLE
    }

    public Evaluation {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.UNSATISFIABLE) != (reason != null)) {
            throw new IllegalArgumentException("Only an unsatisfiable evaluation has a reason.");
        }
    }

    public static Evaluation satisfiable() {
        return new Evaluation(Outcome.SATISFIABLE, null, null);
    }

    public static Evaluation needsApproval() {
        return new Evaluation(Outcome.NEEDS_APPROVAL, null, null);
    }

    public static Evaluation unsatisfiable(String reason, String message) {
        return new Evaluation(Outcome.UNSATISFIABLE, Objects.requireNonNull(reason, "reason"), message);
    }

    /** True when the outcome is {@code SATISFIABLE}. */
    public boolean fits() {
        return outcome == Outcome.SATISFIABLE;
    }
}
