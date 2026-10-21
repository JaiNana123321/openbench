package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceTypeId;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * The type lock and its failure contract with Requests (design Section 5, "The one retry"): a lock
 * wait past {@code lock_timeout} (55P03) or a deadlock (40P01) reaches the caller as Spring's
 * {@code PessimisticLockingFailureException}, after the transaction has rolled back.
 */
@DisplayName("ADR-0006: AllocationLock")
class AllocationLockTest extends AllocationTest {

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("sets lock_timeout to 5 s for the rest of its transaction only")
    void setsAFiveSecondLockTimeoutLocally() {
        long type = exclusiveType();

        String inside = inTransaction(() -> {
            lock.lockTypes(types(type));
            return jdbc.queryForObject("SHOW lock_timeout", String.class);
        });
        String after = inTransaction(() -> jdbc.queryForObject("SHOW lock_timeout", String.class));

        assertThat(inside).isEqualTo("5s");
        assertThat(after).isEqualTo("0");
    }

    @Test
    @DisplayName("holds the row lock until the transaction ends, and locking again is a no-op")
    void holdsTheRowLock() {
        long type = exclusiveType();

        Boolean lockedByUs = inTransaction(() -> {
            lock.lockTypes(types(type));
            lock.lockTypes(types(type));
            return jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                                   WHERE c.relname = 'resource_type' AND l.pid = pg_backend_pid()
                                     AND l.mode = 'RowShareLock')""", Boolean.class);
        });

        assertThat(lockedByUs).isTrue();
    }

    @Test
    @DisplayName("a lock wait beyond lock_timeout surfaces as PessimisticLockingFailureException, rolled back")
    void lockTimeoutSurfacesAsPessimisticLockingFailure() throws Exception {
        long contended = exclusiveType();
        long free = exclusiveType();
        room(free, "Room A");
        LineSnapshot line = exclusiveLine(team, free, window(7, 9, 10));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> inTransaction(() -> {
            lock.lockTypes(types(contended));
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> inTransaction(() -> {
                lock.lockTypes(types(free));
                registry.forClass(line.allocationClass()).hold(line);
                jdbc.execute("SET LOCAL lock_timeout = '300ms'");
                lock.lockTypes(types(contended));
            }))
                    .isInstanceOf(PessimisticLockingFailureException.class)
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("55P03"));
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(occurrencesOf(line)).as("the Hold placed before the timeout was rolled back").isEmpty();
    }

    @Test
    @DisplayName("a deadlock (a waitlist fill locking a second type) surfaces as PessimisticLockingFailureException")
    void deadlockSurfacesAsPessimisticLockingFailure() throws Exception {
        long first = exclusiveType();
        long second = exclusiveType();
        CountDownLatch bothLocked = new CountDownLatch(2);
        List<Future<?>> transitions = new ArrayList<>();
        for (long[] order : new long[][] {{first, second}, {second, first}}) {
            transitions.add(threads.submit(() -> inTransaction(() -> {
                lock.lockTypes(types(order[0]));
                bothLocked.countDown();
                await(bothLocked);
                lock.lockTypes(types(order[1]));
            })));
        }

        List<Throwable> failures = new ArrayList<>();
        for (Future<?> transition : transitions) {
            try {
                transition.get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                failures.add(e.getCause());
            }
        }

        assertThat(failures).singleElement()
                .isInstanceOf(PessimisticLockingFailureException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("40P01"));
    }

    @Test
    @DisplayName("refuses an unknown type and a call outside a transaction")
    void refusesAnUnknownTypeAndNoTransaction() {
        assertThatThrownBy(() -> inTransaction(() -> lock.lockTypes(types(Long.MAX_VALUE))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> lock.lockTypes(types(exclusiveType())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private static TreeSet<ResourceTypeId> types(long... ids) {
        TreeSet<ResourceTypeId> set = new TreeSet<>();
        for (long id : ids) {
            set.add(new ResourceTypeId(id));
        }
        return set;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The other transaction never arrived.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String sqlState(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
