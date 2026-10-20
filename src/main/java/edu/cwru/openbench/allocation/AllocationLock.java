package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ResourceTypeId;
import java.util.SortedSet;

/**
 * Serializes allocation per Resource Type (ADR-0006): every check and write that consumes or reduces
 * a type's capacity comes after this lock, in the same transaction, which keeps it until commit.
 */
public interface AllocationLock {

    /**
     * Sets {@code lock_timeout} to 5 s for the rest of the transaction, then locks the
     * {@code resource_type} row of each type, in ascending id order. Call it once, first, before any
     * account lock. Locking a type again in the same transaction is a no-op.
     *
     * @throws edu.cwru.openbench.common.NotFoundException if a type does not exist
     * @throws org.springframework.dao.PessimisticLockingFailureException on a lock timeout (55P03) or
     *     a deadlock (40P01), propagated unchanged: the caller's transaction rolls back, and the
     *     caller may retry once
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction
     */
    void lockTypes(SortedSet<ResourceTypeId> types);
}
