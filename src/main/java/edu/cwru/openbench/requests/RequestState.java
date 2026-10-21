package edu.cwru.openbench.requests;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The state of a Request (design Table "Request transitions"). Submitted is transient: a submission
 * leaves it in the same transaction. JSON: the upper-case name, such as {@code "DRAFT"}.
 *
 * <p>{@link #canBecome} holds the table's changes of state; an event that leaves the state as it
 * is (a Draft edit, a Release of an Allocated line, a lapse while Waiting) is not a change. Demo 1
 * uses Draft, Submitted, Allocated, Waiting, and Withdrawn; the rest come with Matched lines.
 */
public enum RequestState {
    DRAFT,
    SUBMITTED,
    PENDING,
    WAITING,
    ALLOCATED,
    WITHDRAWN,
    REFUSED;

    private static final Map<RequestState, Set<RequestState>> NEXT = new EnumMap<>(RequestState.class);

    static {
        NEXT.put(DRAFT, EnumSet.of(SUBMITTED, WITHDRAWN));
        NEXT.put(SUBMITTED, EnumSet.of(ALLOCATED, PENDING, WAITING));
        NEXT.put(PENDING, EnumSet.of(ALLOCATED, WAITING, REFUSED, WITHDRAWN));
        NEXT.put(WAITING, EnumSet.of(PENDING, ALLOCATED, DRAFT, REFUSED, WITHDRAWN));
        NEXT.put(ALLOCATED, EnumSet.of(WAITING));
        NEXT.put(WITHDRAWN, EnumSet.noneOf(RequestState.class));
        NEXT.put(REFUSED, EnumSet.noneOf(RequestState.class));
    }

    /** Whether a transition of design Table "Request transitions" leads from this state to {@code next}. */
    public boolean canBecome(RequestState next) {
        return NEXT.get(this).contains(next);
    }
}
