package edu.cwru.openbench.notifications;

/**
 * What a notification is about (design Section 4.4, the table of kinds), which decides nothing but
 * lets the client group notices. JSON: the upper-case name.
 */
public enum NotificationKind {
    /** A lending Manager, or once escalated the Capacity Planners, is asked to approve a Matched line. */
    APPROVAL_REQUESTED,
    /** A Person was selected as a Candidate for a Matched line. */
    CANDIDATE_SELECTED,
    /** A Person's Loan formed. */
    LOAN_FORMED,
    /** The requesting Manager's Request changed state, dropped an Optional line, or needs a replacement. */
    REQUEST_UPDATE,
    /** A standard line was Bumped by a Priority line. */
    BUMPED,
    /** Freed capacity allocated a waiting Request. */
    WAITLIST_FILLED,
    /** An Objection Window lapsed with an approval outstanding. */
    ESCALATED,
    /** A Suggestion was made, accepted, or declined. */
    SUGGESTION,
    /** A Period ended: the Team's granted credits expired and the new Period's Grant posted (S7). */
    PERIOD_ROLLOVER
}
