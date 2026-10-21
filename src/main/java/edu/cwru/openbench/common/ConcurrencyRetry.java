package edu.cwru.openbench.common;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one retry of an allocating transaction (design Section 5, "The one retry"). A lock timeout
 * (55P03) or a deadlock (40P01) reaches the caller as Spring's
 * {@link PessimisticLockingFailureException} after the transaction has rolled back, so the retry
 * wraps the transactional proxy: a controller calls {@code ConcurrencyRetry.once(() ->
 * lifecycle.submit(actor, id))}, and the second call runs in a new transaction. A second failure
 * becomes {@link ConcurrencyRetryException} (HTTP 503, {@code CONCURRENCY_RETRY}); nothing changed.
 */
public final class ConcurrencyRetry {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyRetry.class);

    private ConcurrencyRetry() {}

    /**
     * Runs the call, and once more if it fails on a lock wait or deadlock.
     *
     * @throws ConcurrencyRetryException if the second call fails the same way
     * @throws IllegalStateException if a transaction is already active, since then the retry would
     *     not start a new one
     */
    public static <T> T once(Supplier<T> call) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Retry a transaction from outside it, not from within one.");
        }
        try {
            return call.get();
        } catch (PessimisticLockingFailureException first) {
            log.info("event=retry cause=\"{}\"", first.getMessage());
            try {
                return call.get();
            } catch (PessimisticLockingFailureException second) {
                second.addSuppressed(first);
                throw new ConcurrencyRetryException("Failed twice on a lock wait or deadlock.", second);
            }
        }
    }
}
