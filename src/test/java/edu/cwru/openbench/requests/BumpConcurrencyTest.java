package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import edu.cwru.openbench.allocation.ReleasePart;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.members.SignedInMember;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Bumps under concurrency (ADR-0006): the type lock orders transitions on one room, so a line is
 * Bumped at most once, no instance is given twice, and no exclusion violation leaks. Each call runs
 * as its controller runs it, in its own transaction with the one retry.
 */
@DisplayName("Bumps racing other transitions on one room")
class BumpConcurrencyTest extends BumpWorldTest {

    @Autowired
    private RequestLifecycleService lifecycle;

    @Autowired
    private UserDetailsService users;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @RepeatedTest(5)
    @DisplayName("two Priority submissions for one blocked room: one Bumps the blocking line, once; the other waits")
    void twoPrioritySubmissionsBumpOnce() throws Exception {
        LocalDate d = dayD();
        long[] blocking = submitted(oscar, room, at(d, 10), at(d, 12), roomA, false);
        long a = createDraft(as(alice));
        long aLine = line(as(alice), a, room, 1, at(d, 10), at(d, 12), roomA);
        setPriority(as(alice), a, aLine, true);
        long b = createDraft(as(bob));
        long bLine = line(as(bob), b, room, 1, at(d, 10), at(d, 12), roomA);
        setPriority(as(bob), b, bLine, true);

        List<String> outcomes = race(List.of(
                as(alice, member -> lifecycle.submit(member.actor(), new RequestId(a)).state().name()),
                as(bob, member -> lifecycle.submit(member.actor(), new RequestId(b)).state().name())));

        assertThat(outcomes).containsExactlyInAnyOrder("ALLOCATED", "WAITING");
        assertThat(rowStates(blocking[1])).containsExactly("BUMPED");
        assertThat(refundsOf(blocking[1])).containsExactly("30.00");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exclusive_occurrence WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED')""",
                Integer.class, roomA)).isEqualTo(1);
        boolean aliceWon = outcomes.getFirst().equals("ALLOCATED");
        assertThat(rowStates(aliceWon ? aLine : bLine)).containsExactly("ALLOCATED");
        assertThat(rowStates(aliceWon ? bLine : aLine)).isEmpty();
        assertThat(waitlistEntryOf(aliceWon ? bLine : aLine)).containsExactly("false/true");
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).hasSize(1);
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    @RepeatedTest(5)
    @DisplayName("a Bump racing a Release of the blocking line: the room is the Priority line's, and one Refund posts")
    void aBumpRacesARelease() throws Exception {
        LocalDate d = dayD();
        long[] blocking = submitted(oscar, room, at(d, 10), at(d, 12), roomB, false);
        long a = createDraft(as(alice));
        long aLine = line(as(alice), a, room, 1, at(d, 10), at(d, 12), roomB);
        setPriority(as(alice), a, aLine, true);

        List<String> outcomes = race(List.of(
                as(alice, member -> lifecycle.submit(member.actor(), new RequestId(a)).state().name()),
                as(oscar, member -> lifecycle.releaseLine(member.actor(), new RequestId(blocking[0]),
                        new RequestLineId(blocking[1]), new ReleasePart.WholeLine()).line().state().name())));

        assertThat(outcomes.getFirst()).isEqualTo("ALLOCATED");
        assertThat(rowStates(aLine)).containsExactly("ALLOCATED");
        if (outcomes.get(1).equals("RELEASED")) {
            // The Release ran first: the Priority line found Room B free and Bumped nothing.
            assertThat(rowStates(blocking[1])).containsExactly("RELEASED");
            assertThat(refundsOf(blocking[1])).containsExactly("22.50");
            assertThat(noticesOf(world.oscar().id(), "BUMPED")).isEmpty();
        } else {
            // The Bump ran first: the line waits, so there was nothing left to release.
            assertThat(outcomes.get(1)).isEqualTo("RuleViolation: NOT_ALLOCATED");
            assertThat(rowStates(blocking[1])).containsExactly("BUMPED");
            assertThat(refundsOf(blocking[1])).containsExactly("30.00");
            detail(as(oscar), blocking[0]).andExpect(jsonPath("$.request.state").value("WAITING"));
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exclusive_occurrence WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED')""",
                Integer.class, roomB)).isEqualTo(1);
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    /** A call as this Member, signed in on the calling thread, through the one retry. */
    private Callable<String> as(String username, java.util.function.Function<SignedInMember, String> call) {
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

    /** Runs the calls at once, released by one latch, and returns each one's result or refusal code, in order. */
    private List<String> race(List<Callable<String>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (Callable<String> call : calls) {
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
                outcomes.add(e.getCause() instanceof RuleViolation refused
                        ? "RuleViolation: " + refused.code()
                        : e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
        }
        return outcomes;
    }
}
