package edu.cwru.openbench.allocation;

import java.util.Optional;

/**
 * The capability of a class whose higher-ranked lines Bump lower-ranked ones: on Exclusive, Priority
 * lines Bump standard ones (ADR-0003; SRS Exclusive.Priority.Bump); on Metered, a line of a service
 * tier Bumps lines of strictly lower tiers (SRS Metered.Tier.Bump). Requests asks
 * {@link AllocatorRegistry#capability} for it.
 *
 * <p>Both operations join the caller's transaction, which has locked the line's type, and the plan
 * is made and applied in that one transaction. A line is never Bumped by one of its own rank or
 * lower, and no line with an occurrence starting before now plus the type's bump cutoff is Bumped.
 */
public interface Bumpable {

    /**
     * The Bump that would let a Priority line fit: the instances needing the fewest Bumps of standard
     * lines, Held or Allocated, ties toward the latest-submitted (on Metered, the lower-tier lines
     * whose units make room in every hour). Empty when the line is standard (has no tier), fits
     * without a Bump, or too little can be cleared. Writes nothing.
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
