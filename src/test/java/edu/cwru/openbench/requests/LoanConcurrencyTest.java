package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.allocation.Decision;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.members.SignedInMember;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * RC-1 (ADR-0006): a Person is in at most one Loan on any day. Two Requests of different Teams have
 * Dave Kim selected for overlapping days, and his Manager approves both at the same moment. Each
 * decision runs as the controller calls it, in its own transaction with the one retry. The Person
 * type's lock makes the second decision see the first's Loan: exactly one Loan forms, the other
 * approval is refused with {@code NOT_A_CANDIDATE}, and its Request stays Pending.
 */
@DisplayName("RC-1: two concurrent approvals that would lend one Person twice")
class LoanConcurrencyTest extends MatchedWorldTest {

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
    @DisplayName("exactly one Loan forms; the other approval is refused with NOT_A_CANDIDATE")
    void exactlyOneLoanForms() throws Exception {
        LocalDate d = dayD();
        long a = createDraft(as(alice));
        long am1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a).andExpect(status().isOk());
        select(as(alice), a, am1, world.dave().id()).andExpect(status().isOk());
        long b = createDraft(as(bob));
        long bm1 = personLine(as(bob), b, 1, d.plusDays(1), d.plusDays(2), statistics);
        submit(as(bob), b).andExpect(status().isOk());
        select(as(bob), b, bm1, world.dave().id()).andExpect(status().isOk());

        List<String> outcomes = race(oscar, List.of(approvalOf(am1), approvalOf(bm1)));

        assertThat(outcomes).as("outcomes").containsExactlyInAnyOrder("APPROVED", "RuleViolation: NOT_A_CANDIDATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE member_id = ?", Integer.class,
                world.dave().id())).as("Dave's Loans").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE kind = 'EARNING' AND team_id = ?",
                Integer.class, operations)).as("Operations' Earnings").isEqualTo(1);
        boolean aliceWon = outcomes.getFirst().equals("APPROVED");
        detail(as(aliceWon ? alice : bob), aliceWon ? a : b).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        detail(as(aliceWon ? bob : alice), aliceWon ? b : a).andExpect(jsonPath("$.request.state").value("PENDING"));
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    /**
     * Decides each approval as the approver, both released by one latch, and returns per approval its
     * new state or the refusal's code.
     */
    private List<String> race(String approver, List<Long> approvals) throws Exception {
        SignedInMember member = (SignedInMember) users.loadUserByUsername(approver);
        CountDownLatch ready = new CountDownLatch(approvals.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (long approval : approvals) {
            attempts.add(threads.submit(() -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(member, null,
                        member.getAuthorities()));
                SecurityContextHolder.setContext(context);
                try {
                    ready.countDown();
                    go.await();
                    return ConcurrencyRetry.once(() -> lifecycle.decideApproval(member.actor(), new ApprovalId(approval),
                            Decision.APPROVE, "")).state().name();
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
