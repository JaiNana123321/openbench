package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.members.SignedInMember;
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
 * QA-correct (ADR-0006): two Managers of different Teams submit at the same moment for the last
 * free room. Each thread has its own security context and calls {@code submit} as the controller
 * does, in its own transaction with the one retry. The type lock makes the second evaluation see
 * the first's rows: exactly one Request is Allocated and the other Waiting at position 1, and both
 * Accounts still equal the fold of their Transactions.
 */
@DisplayName("QA-correct: two concurrent submissions for the last room")
class SubmitConcurrencyTest extends DemoWorldTest {

    @Autowired
    private RequestLifecycleService lifecycle;

    @Autowired
    private UserDetailsService users;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    private long lastRoom;

    @BeforeEach
    void createATypeWithOneRoom() {
        lastRoom = testData().resourceType(unique("Meeting room"), "EXCLUSIVE");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 10.00)", lastRoom);
        long owner = jdbc.queryForObject("SELECT owner_member_id FROM resource WHERE id = ?", Long.class, roomA);
        testData().resource(lastRoom, unique("The last room"), owner, 1);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @RepeatedTest(5)
    @DisplayName("exactly one Request is Allocated, the other Waiting at position 1, and both Accounts fold")
    void theLastRoomGoesToOneRequest() throws Exception {
        long aliceRequest = draftForTheLastRoom(alice);
        long bobRequest = draftForTheLastRoom(bob);

        List<String> outcomes = race(List.of(alice, bob), List.of(aliceRequest, bobRequest));

        assertThat(outcomes).as("outcomes").containsExactlyInAnyOrder("ALLOCATED", "WAITING");
        long waiting = outcomes.get(0).equals("WAITING") ? aliceRequest : bobRequest;
        String waitingManager = waiting == aliceRequest ? alice : bob;
        detail(as(waitingManager), waiting)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exclusive_occurrence o JOIN resource r ON r.id = o.resource_id
                WHERE r.type_id = ? AND o.state IN ('HELD', 'ALLOCATED')""", Integer.class, lastRoom))
                .as("live occurrences on the last room").isEqualTo(1);
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE kind = 'CHARGE' AND team_id IN (?, ?)",
                Integer.class, platform, research)).as("one Charge, for the winner").isEqualTo(1);
    }

    private long draftForTheLastRoom(String manager) throws Exception {
        long request = createDraft(as(manager));
        line(as(manager), request, lastRoom, 1, tuesdayAt(14), tuesdayAt(16), null);
        return request;
    }

    /**
     * Submits each Request as its Manager, both released by one latch, and returns per Request its
     * new state or the exception that ended the submission.
     */
    private List<String> race(List<String> managers, List<Long> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(managers.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (int i = 0; i < managers.size(); i++) {
            SignedInMember member = (SignedInMember) users.loadUserByUsername(managers.get(i));
            RequestId request = new RequestId(requests.get(i));
            attempts.add(threads.submit(() -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(member, null,
                        member.getAuthorities()));
                SecurityContextHolder.setContext(context);
                try {
                    ready.countDown();
                    go.await();
                    return ConcurrencyRetry.once(() -> lifecycle.submit(member.actor(), request)).state().name();
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
