package edu.cwru.openbench.requests;

/**
 * The state of a Request (design Table "Request transitions"). Submitted is transient: a submission
 * leaves it in the same transaction. JSON: the upper-case name, such as {@code "DRAFT"}.
 */
public enum RequestState {
    DRAFT,
    SUBMITTED,
    PENDING,
    WAITING,
    ALLOCATED,
    WITHDRAWN,
    REFUSED
}
