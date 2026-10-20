package edu.cwru.openbench.allocation;

import edu.cwru.openbench.inventory.AllocationClass;

/**
 * The one interface every Allocation Class implements (ADR-0007): Requests drives a line's whole
 * lifecycle through these five operations without branching on its class. Anything only some
 * classes do is a capability, such as {@link Waitlistable}, found through {@link AllocatorRegistry}.
 *
 * <p>Every operation joins the caller's transaction and refuses to run without one
 * ({@code Propagation.MANDATORY}), so any exception rolls back the whole transition. The caller
 * locks the line's type with {@link AllocationLock#lockTypes} first, which makes evaluating and then
 * holding one serial step.
 */
public interface Allocator {

    AllocationClass allocationClass();

    /** Whether the line fits now. Precondition: the line's type is locked. */
    Evaluation evaluate(LineSnapshot line);

    /**
     * Blocks the line's capacity with rows in state HELD, which block exactly like allocated ones.
     * The fit is checked again under the lock.
     *
     * @throws edu.cwru.openbench.common.RuleViolation with the misfit's code if the line does not fit
     * @throws IllegalStateException if the line already has live rows
     */
    void hold(LineSnapshot line);

    /**
     * Makes the line final: its HELD rows become ALLOCATED. A line with no Hold (a Waiting Request
     * allocated with no Hold first) gets ALLOCATED rows after the fit is checked again. Either way
     * the line is all or nothing.
     *
     * @throws edu.cwru.openbench.common.RuleViolation with the misfit's code if a line without a Hold does not fit
     * @throws IllegalStateException if the line is already allocated
     */
    void commit(LineSnapshot line);

    /**
     * Gives back allocated capacity: the rows become RELEASED, so history stays.
     *
     * @throws edu.cwru.openbench.common.RuleViolation {@code NOT_ALLOCATED} if the line has no allocated rows
     */
    void release(LineSnapshot line, ReleasePart part);

    /** Abandons the line before allocation: its Holds become EXPIRED and it leaves the waitlist. Idempotent. */
    void cancel(LineSnapshot line);
}
