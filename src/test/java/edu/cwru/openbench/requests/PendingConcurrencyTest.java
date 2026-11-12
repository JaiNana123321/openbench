package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.members.SignedInMember;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Lifecycle.Hold.Place under concurrency (ADR-0006): two Pending Requests of one Team, submitted at
 * the same moment, each holding 480.00 of the Team's 600.00. Each passes the early check alone; under
 * the account lock the second Hold no longer fits, so exactly one Request is Pending and the other is
 * refused with {@code INSUFFICIENT_BALANCE}, and the Account still folds.
 */
@DisplayName("Lifecycle.Hold.Place: two concurrent Pending Requests cannot hold more than the Available Balance")
class PendingConcurrencyTest extends MatchedWorldTest {

    @Autowired
    private RequestLifecycleService lifecycle;

    @Autowired
    private UserDetailsService users;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    private long microscopes;

    @BeforeEach
    void createTwoMicroscopes() {
        microscopes = testData().resourceType(unique("Confocal microscope"), "EXCLUSIVE");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 40.00)", microscopes);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '12345', '09:00', '17:00', 1.5)",
                microscopes);
        long owner = jdbc.queryForObject("SELECT owner_member_id FROM resource WHERE id = ?", Long.class, roomA);
        testData().resource(microscopes, unique("Microscope 1"), owner, 1);
        testData().resource(microscopes, unique("Microscope 2"), owner, 1);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @RepeatedTest(3)
    @Verifies(value = "lif-held-credits", partial = true)
    @DisplayName("exactly one is Pending holding 480.00; the other is refused, and the Account folds")
    void twoPendingRequestsCannotHoldMoreThanTheBalance() throws Exception {
        LocalDate d = dayD();
        long first = pendingDraft(d);
        long second = pendingDraft(d);

        List<String> outcomes = race(bob, List.of(first, second));

        assertThat(outcomes).as("outcomes").containsExactlyInAnyOrder("PENDING", "INSUFFICIENT_BALANCE");
        assertThat(account(research)).containsEntry("held", new BigDecimal("480.00"));
        assertThat(available(research)).isEqualByComparingTo("120.00");
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exclusive_occurrence o JOIN resource r ON r.id = o.resource_id
                WHERE r.type_id = ? AND o.state = 'HELD'""", Integer.class, microscopes)).as("Holds").isEqualTo(1);
    }

    /** Bob's Draft: a Person line for Java (Ben) and a microscope, D 09:00-17:00, 480.00. */
    private long pendingDraft(LocalDate d) throws Exception {
        long draft = createDraft(as(bob));
        personLine(as(bob), draft, 1, d, d.plusDays(1), java);
        line(as(bob), draft, microscopes, 1, at(d, 9), at(d, 17), null);
        return draft;
    }

    /** Submits each Request as the Manager, both released by one latch; per Request its state or refusal code. */
    private List<String> race(String manager, List<Long> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        SignedInMember member = (SignedInMember) users.loadUserByUsername(manager);
        List<Future<String>> attempts = new ArrayList<>();
        for (long id : requests) {
            RequestId request = new RequestId(id);
            attempts.add(threads.submit(() -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(member, null,
                        member.getAuthorities()));
                SecurityContextHolder.setContext(context);
                try {
                    ready.countDown();
                    go.await();
                    return ConcurrencyRetry.once(() -> lifecycle.submit(member.actor(), request)).state().name();
                } catch (RuleViolation refused) {
                    return refused.code();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> attempt : attempts) {
            try {
                outcomes.add(attempt.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                outcomes.add(e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
        }
        return outcomes;
    }
}
