package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceTypeId;
import java.util.SortedSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The type lock (ADR-0006, design Section 5): one {@code SELECT ... FOR UPDATE} on
 * {@code resource_type} per type, ascending, at READ COMMITTED, held to the end of the caller's
 * transaction. The same row lock Inventory takes before a capacity change, so neither can slip in
 * between the other's check and write.
 *
 * <p>Before locking it sets {@code lock_timeout} to 5 s with {@code set_config(..., true)}, the
 * function form of {@code SET LOCAL}, so the limit ends with the transaction and covers every later
 * lock in it, the account lock included. A timeout the transaction already set is kept, which lets
 * a test shorten the wait. A lock timeout (55P03) or deadlock (40P01) reaches the caller as Spring's
 * {@code PessimisticLockingFailureException}, unchanged; the caller retries.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class TypeRowLock implements AllocationLock {

    static final String LOCK_TIMEOUT = "5s";

    private final JdbcTemplate jdbc;

    TypeRowLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockTypes(SortedSet<ResourceTypeId> types) {
        jdbc.queryForList("SELECT set_config('lock_timeout', ?, true) WHERE current_setting('lock_timeout') = '0'",
                String.class, LOCK_TIMEOUT);
        types.stream().map(ResourceTypeId::value).sorted().distinct().forEach(id -> {
            if (jdbc.queryForList("SELECT id FROM resource_type WHERE id = ? FOR UPDATE", Long.class, id).isEmpty()) {
                throw new NotFoundException("Resource Type " + id + " does not exist.");
            }
        });
    }
}
