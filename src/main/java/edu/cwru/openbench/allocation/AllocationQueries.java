package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only questions about live (HELD or ALLOCATED) rows and the waitlist. */
public interface AllocationQueries {

    /**
     * The line's place on its type's waitlist, counting from 1: one more than the number of entries
     * of the same type, with an overlapping window, that come before it in waitlist order (Bumped,
     * then Priority, then submission time, then entry id). Empty when the line is not waiting.
     */
    Optional<Integer> waitlistPosition(RequestLineId line);

    /**
     * The Resources an Exclusive line's live (HELD or ALLOCATED) occurrences are on, by id; empty
     * for a Metered line, whose units come from the pool, and for a line with no live rows.
     */
    List<ResourceId> resourcesOf(RequestLineId line);

    /**
     * The largest hourly sum of live units from {@code from} on, Holds counted: of the type's Metered
     * pool (then {@code instance} is ignored), or for an Exclusive type the live occurrences on
     * {@code instance}, or on all its instances when empty. Hours start on the hour; the first is
     * cut at {@code from}.
     */
    int peakUnitsInUse(ResourceTypeId type, Optional<ResourceId> instance, Instant from);

    /**
     * The waitlist entries, of every type, whose window started at or before {@code now}, by entry
     * id: the lines {@code expireWaitingLines} takes off the waitlist (SRS Exclusive.Waitlist.Expire).
     * An entry whose window starts after {@code now} is never among them.
     */
    List<WaitingLine> waitingStartedBy(Instant now);

    /**
     * What {@link Allocator#release} would give back for this part, without writing or locking, so a
     * Manager sees the Refund before confirming.
     *
     * @throws edu.cwru.openbench.common.RuleViolation the codes of {@link Allocator#release}
     */
    Released previewRelease(LineSnapshot line, ReleasePart part);

    /**
     * The line's ALLOCATED parts, one per occurrence by start: its window and its instances
     * (Exclusive) or units (Metered). Empty for a line with nothing allocated.
     */
    List<AllocatedPart> allocatedParts(RequestLineId line);

    /**
     * The Outstanding approvals addressed to this Manager that are not escalated, by id: those the
     * Manager still decides. Escalated ones are in {@link #escalatedOutstanding()}.
     */
    List<Approval> approvalsAwaiting(MemberId approver);

    /** One approval, in any state; empty when it does not exist. */
    Optional<Approval> approval(ApprovalId id);

    /** Every approval of a Matched line, in the order its Candidates were selected. */
    List<Approval> approvalsOf(RequestLineId line);

    /** Every approval of a Request's Matched lines, in the order their Candidates were selected. */
    List<Approval> approvalsOfRequest(RequestId request);

    /** The Requests with an Outstanding approval not yet escalated, by id: what a lapse acts on. */
    Set<RequestId> requestsWithUnescalatedOutstanding();

    /** The Loans a Matched line's commit formed, by id; empty before its commit (for {@code postEarning}). */
    List<Loan> loansFor(RequestLineId line);

    /** Every Loan of the Member, past and future, by first day. Unscoped: callers check who may see them. */
    List<Loan> loansOf(MemberId member);

    /**
     * An Exclusive Resource's timeline over the half-open window (SRS Inventory.View.Holder): ordered
     * parts that cover it exactly, each free, HELD, or ALLOCATED, the latter two with the holding Team
     * and Request and the Priority mark. Only live rows count; touching parts of the same holder are
     * one. Unscoped: callers decide which holders the caller may see.
     */
    List<Holding> holders(ResourceId resource, TimeWindow window);

    /** Every escalated Outstanding approval, by id: what the Capacity Planners decide (SRS Matched.Escalate). */
    List<Approval> escalatedOutstanding();

    /**
     * The escalated approvals addressed to this lending Manager that someone else, a Capacity
     * Planner, decided, by id: what the Manager's Approvals screen shows as decided for them.
     */
    List<Approval> decidedOnEscalation(MemberId approver);
}
