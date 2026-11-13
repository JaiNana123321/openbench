package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.allocation.Decision;
import edu.cwru.openbench.allocation.ReleasePart;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.members.SignedInMember;
import java.sql.Timestamp;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * A Capacity Planner's approval racing a waitlist fill for the same room (issue #7). After A1's
 * Objection Window lapsed, its Room A line waits behind Oscar's allocation, and Bob's X waits after
 * it. Erin approves A1's escalated approval while Oscar releases Room A, each as the controller calls
 * it, in its own transaction with the one retry. The type lock orders them: whichever runs second
 * sees the first's result, so Room A is allocated exactly once, to A1 or to X, and never held twice.
 */
@DisplayName("A Planner's approval racing a waitlist fill for the same room")
class EscalationConcurrencyTest extends EscalationWorldTest {

    @Autowired
    private UserDetailsService users;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @RepeatedTest(5)
    @DisplayName("exactly one allocation of Room A, and no double Hold")
    void exactlyOneAllocation() throws Exception {
        LocalDate d = dayD();
        long o1 = createDraft(as(oscar));
        long ol = line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("ALLOCATED"));

        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        line(as(alice), a1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a1, m1, world.dave().id()).andExpect(status().isOk());
        lapse(a1);
        long x = createDraft(as(bob));
        line(as(bob), x, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(bob), x).andExpect(jsonPath("$.state").value("WAITING"));
        long approval = approvalOf(m1);

        List<String> outcomes = race(
                List.of(erin, oscar),
                List.of(member -> () -> ConcurrencyRetry.once(() -> lifecycle.decideApproval(member.actor(),
                                new ApprovalId(approval), Decision.APPROVE, "")).state().name(),
                        member -> () -> ConcurrencyRetry.once(() -> lifecycle.releaseLine(member.actor(),
                                new RequestId(o1), new RequestLineId(ol), new ReleasePart.WholeLine())).line().state()
                                .name()));

        assertThat(outcomes).as("outcomes").containsExactly("APPROVED", "RELEASED");
        assertThat(jdbc.queryForList("""
                SELECT request_id FROM exclusive_occurrence
                WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED') AND starts_at < ? AND ends_at > ?""",
                Long.class, roomA, Timestamp.from(at(d, 12)), Timestamp.from(at(d, 10))))
                .as("Room A's live rows on D 10:00-12:00")
                .singleElement()
                .isIn(a1, x);
        List<String> states = List.of(stateOf(a1), stateOf(x));
        assertThat(states).as("A1 and X").containsAnyOf("ALLOCATED").doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit h JOIN request_line l ON l.id = h.line_id"
                + " WHERE l.request_id IN (?, ?)", Integer.class, a1, x)).as("held credits").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE line_id = ?", Integer.class, m1))
                .as("Dave's Loan, only if A1 won")
                .isEqualTo(stateOf(a1).equals("ALLOCATED") ? 1 : 0);
    }

    private String stateOf(long request) {
        return jdbc.queryForObject("SELECT state FROM request WHERE id = ?", String.class, request);
    }

    /**
     * Runs each call as its Member, all released by one latch, and returns per call its result or
     * the refusal's code.
     */
    private List<String> race(List<String> usernames, List<Function<SignedInMember, Callable<String>>> calls)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            SignedInMember member = (SignedInMember) users.loadUserByUsername(usernames.get(i));
            Callable<String> call = calls.get(i).apply(member);
            attempts.add(threads.submit(() -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(member, null,
                        member.getAuthorities()));
                SecurityContextHolder.setContext(context);
                try {
                    ready.countDown();
                    go.await();
                    return call.call();
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
                outcomes.add(e.getCause() instanceof RuleViolation refused
                        ? "RuleViolation: " + refused.code()
                        : e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
        }
        return outcomes;
    }
}
