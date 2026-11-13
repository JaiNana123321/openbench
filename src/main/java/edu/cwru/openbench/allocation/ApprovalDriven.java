package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import java.util.List;

/**
 * The capability of a class whose lines are gated by a lending Manager's approval (ADR-0007):
 * Matched. Every operation joins the caller's transaction; the caller locks the line's type first.
 */
public interface ApprovalDriven {

    /**
     * The line's Shortlist, computed now so profile changes show at once: Members of other Teams who
     * are active, hold every skill, are available every day of the window, and have no Loan
     * overlapping it, by person-days lent in the current Period, then name. For a line naming one
     * Person, that Person alone if eligible, else nobody.
     */
    Shortlist buildShortlist(LineSnapshot line);

    /**
     * The requesting Manager's selection, or a named Person at submission: one {@code candidate} row
     * and one Outstanding approval per Member, addressed to the Manager of the Member's Team, who is
     * notified ({@code APPROVAL_REQUESTED}) as is the Member ({@code CANDIDATE_SELECTED}).
     *
     * @throws edu.cwru.openbench.common.RuleViolation {@code LINE_NOT_ATOMIC} unless the new and the
     *     Outstanding or Approved Candidates together are exactly the line's quantity;
     *     {@code OWN_TEAM} for a Member of the requesting Team; {@code NOT_A_CANDIDATE} for a Member
     *     not on the Shortlist or already selected for the line
     */
    default void select(LineSnapshot line, List<MemberId> candidates) {
        select(line, candidates, false);
    }

    /**
     * {@link #select(LineSnapshot, List)}; with {@code escalated}, after the Request's Objection
     * Window lapsed (SRS Matched.Escalate), each approval is created escalated and goes to the
     * Capacity Planners, who are notified ({@code APPROVAL_REQUESTED}) instead of the lending Manager.
     */
    void select(LineSnapshot line, List<MemberId> candidates, boolean escalated);

    /**
     * Whether a replacement can still satisfy the line: it names no Person, and its Shortlist holds,
     * besides the Members already selected for it in any state, at least as many Members as its
     * Outstanding and Approved Candidates fall short of its quantity (design S3, "no Candidate is
     * left").
     */
    boolean candidatesLeft(LineSnapshot line);

    /**
     * Records a decision on an Outstanding approval with its actor, reason, and time: the lending
     * Manager's until the Objection Window ends, a Capacity Planner's after it (SRS
     * Matched.Escalate). {@code windowLapsed} says whether the Request's Objection Window has ended,
     * which only Requests knows. A Capacity Planner's decision sets {@code escalated}. Returns the
     * approval as decided.
     *
     * @throws edu.cwru.openbench.common.NotFoundException for an unknown approval
     * @throws edu.cwru.openbench.common.ForbiddenException unless the actor is the approver or a
     *     Capacity Planner, or for a Capacity Planner before the window ended
     * @throws edu.cwru.openbench.common.RuleViolation {@code ALREADY_DECIDED} unless Outstanding;
     *     {@code WINDOW_LAPSED} for the lending Manager after the window ended or the approval was
     *     escalated; {@code NOT_A_CANDIDATE} when approving a Candidate no longer eligible (a Loan
     *     formed elsewhere, or availability changed)
     */
    Approval decide(LineSnapshot line, ApprovalId id, Decision decision, String reason, Actor actor,
            boolean windowLapsed);

    /**
     * The system refuses, with the reason, every Approved approval of the line whose Candidate is no
     * longer eligible (unavailable on a day of the line, or lent then on another line), so that the
     * requesting Manager can select a replacement; returns those refused. What allocation does when
     * {@link Allocator#evaluate} answers {@code UNSATISFIABLE(NOT_A_CANDIDATE)} (design
     * {@code allocate}, step a).
     */
    List<Approval> refuseIneligible(LineSnapshot line, String reason);

    /** The Request's Outstanding approvals, by id. */
    List<Approval> outstanding(RequestId request);

    /** Sets {@code escalated} on every Outstanding approval of the Request not yet escalated, and returns those. */
    List<Approval> escalate(RequestId request);
}
