package edu.cwru.openbench.allocation;

/**
 * The state of an approval (design Section 4.5, Data owned). Escalation is a flag, not a state.
 * JSON: the upper-case name.
 */
public enum ApprovalState {
    /** Waiting for the lending Manager's decision. */
    OUTSTANDING,
    APPROVED,
    REFUSED,
    /** The Request was withdrawn, or the line abandoned, before the Loan formed. */
    CANCELED
}
