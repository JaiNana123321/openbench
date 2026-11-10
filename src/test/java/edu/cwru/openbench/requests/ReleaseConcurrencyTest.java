package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import edu.cwru.openbench.allocation.ReleasePart;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.members.SignedInMember;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
 * QA-correct for the waitlist fill (ADR-0006): alice releases the last room while bob's Request waits
 * for it, and at the same moment oscar submits a Request for the same room. Each thread calls the
 * service as the controller does, in its own transaction with the one retry. The type lock makes
 * the Release and its fill one step, so there is exactly one winner, and it is the line that was
 * waiting: oscar's submission either sees the room still held or sees it already given to bob.
 */
@DisplayName("QA-correct: a Release-and-fill races a submission for the same room")
class ReleaseConcurrencyTest extends ReleaseTest {

    @Autowired
    private RequestLifecycleService lifecycle;

    @Autowired
    private UserDetailsService users;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    private long lastRoomType;
    private long lastRoom;

    @BeforeEach
    void createATypeWithOneRoom() {
        lastRoomType = testData().resourceType(unique("Meeting room"), "EXCLUSIVE");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 10.00)", lastRoomType);
        jdbc.update("INSERT INTO refund_step (type_id, min_lead_hours, fraction) VALUES (?, 0, 0.25), (?, 168, 0.75)",
                lastRoomType, lastRoomType);
        long owner = jdbc.queryForObject("SELECT owner_member_id FROM resource WHERE id = ?", Long.class, roomA);
        lastRoom = testData().resource(lastRoomType, unique("The last room"), owner, 1);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @RepeatedTest(5)
    @DisplayName("exactly one Request gets the room, the waiting one, and every Account folds")
    void theFreedRoomGoesToOneRequest() throws Exception {
        long[] a1 = submitted(alice, lastRoomType, 1, tuesdayAt(14), tuesdayAt(16), null);
        long[] b1 = submitted(bob, lastRoomType, 1, tuesdayAt(14), tuesdayAt(16), null);
        assertThat(requestState(bob, b1[0])).isEqualTo("WAITING");
        long o1 = createDraft(as(oscar));
        line(as(oscar), o1, lastRoomType, 1, tuesdayAt(14), tuesdayAt(16), null);

        List<String> outcomes = race(
                calling(alice, member -> lifecycle.releaseLine(member.actor(), new RequestId(a1[0]), new RequestLineId(a1[1]),
                        ReleasePart.wholeLine()).line().state().name()),
                calling(oscar, member -> lifecycle.submit(member.actor(), new RequestId(o1)).state().name()));

        assertThat(outcomes).as("outcomes").containsExactly("RELEASED", "WAITING");
        assertThat(requestState(bob, b1[0])).as("the waiting Request").isEqualTo("ALLOCATED");
        detail(as(oscar), o1)
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
        assertThat(jdbc.queryForList("""
                SELECT line_id FROM exclusive_occurrence
                WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED')""", Long.class, lastRoom))
                .as("live occurrences on the last room").containsExactly(b1[1]);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE kind = 'CHARGE' AND team_id = ?",
                Integer.class, operations)).as("no Charge for oscar").isZero();
        for (long team : new long[] {platform, research, operations}) {
            assertThat(account(team)).isEqualTo(foldOfTransactions(team));
        }
    }

    /** The call, run as the Member in a thread of its own with the controller's one retry. */
    private Callable<String> calling(String username, Function<SignedInMember, String> call) {
        SignedInMember member = (SignedInMember) users.loadUserByUsername(username);
        return () -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(member, null,
                    member.getAuthorities()));
            SecurityContextHolder.setContext(context);
            try {
                return ConcurrencyRetry.once(() -> call.apply(member));
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    /** Runs the calls, released by one latch, and returns each one's result or the exception that ended it. */
    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (Callable<String> call : List.of(first, second)) {
            attempts.add(threads.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
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
