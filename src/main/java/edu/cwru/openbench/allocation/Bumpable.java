package edu.cwru.openbench.allocation;

import java.util.Optional;

/**
 * The capability of a class whose Priority lines Bump standard ones (ADR-0003; SRS
 * Exclusive.Priority.Bump): Exclusive only. Requests asks {@link AllocatorRegistry#capability} for
 * it, so the Priority mark is offered for exactly the classes that have it.
 *
 * <p>Both operations join the caller's transaction, which has locked the line's type, and the plan
 * is made and applied in that one transaction. A Priority line is never Bumped, and no line with an
 * occurrence starting before now plus the type's bump cutoff is Bumped.
 */
public interface Bumpable {

    /**
     * The Bump that would let a Priority line fit: the instances needing the fewest Bumps of standard
     * lines, Held or Allocated, ties toward the latest-submitted. Empty when the line is standard,
     * fits without a Bump, or too few instances can be cleared. Writes nothing.
     */
    Optional<BumpPlan> planBump(LineSnapshot priorityLine);

    /**
     * Applies the plan: the Bumped lines' live rows become BUMPED and are flushed, and only then the
     * Priority line's occurrences are written as HELD on the plan's instances, so the exclusion
     * constraint checks them against the rows that stay live. Requests then settles each Bumped line
     * (Refund or held credits, waitlist, notice) and holds or commits the Priority line's credits.
     *
     * @throws edu.cwru.openbench.common.RuleViolation {@code INSIDE_BUMP_CUTOFF} if the plan is stale:
     *     planning again now gives another plan, such as when a Bumped line's occurrence now starts
     *     inside the cutoff
     */
    void applyBump(BumpPlan plan);
}
