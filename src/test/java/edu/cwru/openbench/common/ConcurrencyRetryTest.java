package edu.cwru.openbench.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;

/** The one retry of design Section 5, with a stub in place of the transactional proxy. */
class ConcurrencyRetryTest {

    @Test
    @DisplayName("a call that fails once on a lock succeeds on the second try")
    void retriesOnce() {
        Stub stub = new Stub(1);

        assertThat(ConcurrencyRetry.once(stub)).isEqualTo("ALLOCATED");
        assertThat(stub.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a call that fails twice on a lock becomes ConcurrencyRetryException, after two tries")
    void givesUpAfterTheSecondFailure() {
        Stub stub = new Stub(2);

        assertThatThrownBy(() -> ConcurrencyRetry.once(stub))
                .isInstanceOf(ConcurrencyRetryException.class)
                .hasCauseInstanceOf(PessimisticLockingFailureException.class);
        assertThat(stub.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("any other failure is not retried")
    void doesNotRetryOtherFailures() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> ConcurrencyRetry.once(() -> {
            calls.incrementAndGet();
            throw new RuleViolation("INSUFFICIENT_BALANCE", "Short.");
        })).isInstanceOf(RuleViolation.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    /** Fails with a lock timeout the first {@code failures} times, then answers. */
    private static final class Stub implements Supplier<String> {

        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        Stub(int failures) {
            this.failures = failures;
        }

        @Override
        public String get() {
            if (calls.incrementAndGet() <= failures) {
                throw new CannotAcquireLockException("canceling statement due to lock timeout");
            }
            return "ALLOCATED";
        }
    }
}
