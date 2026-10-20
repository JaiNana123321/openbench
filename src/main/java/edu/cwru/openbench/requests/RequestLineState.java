package edu.cwru.openbench.requests;

/**
 * The state of a Request Line (design Section 4.8, "Line states"). Every line starts in Draft.
 * JSON: the upper-case name, such as {@code "DRAFT"}.
 */
public enum RequestLineState {
    DRAFT,
    EVALUATED,
    HELD,
    ALLOCATED,
    WAITING,
    RELEASED,
    PARTLY_RELEASED,
    BUMPED,
    AWAITING_APPROVAL,
    APPROVED,
    LOANED,
    REFUSED,
    DROPPED,
    CANCELED
}
