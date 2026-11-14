package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.allocation.Decision;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.LoanId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.members.SignedInMember;
import java.sql.Date;
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
 * RC-1 (ADR-0006) with an early Loan end: Dave Kim is lent to Research for D to D+2, and Platform's
 * Request has him approved-pending for D+2. At the same moment Bob ends the Loan early on D+1 and
 * Oscar approves Dave for Platform. Each call runs as the controller calls it, in its own transaction
 * with the one retry. Both take the Person type's lock, so they run one after the other: either the
 * end commits first and Platform's Loan forms on the freed day, or the approval runs first and is
 * refused with {@code NOT_A_CANDIDATE}. Never two overlapping Loans, and never an exclusion
 * violation reaching the caller.
 */
@DisplayName("RC-1: an early Loan end racing a new Loan on the freed day")
class LoanEndConcurrencyTest extends LoanEndWorldTest {

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
    @DisplayName("the new Loan forms after the end commits, or is refused; never an overlap")
    void theNewLoanFormsAfterTheEndOrIsRefused() throws Exception {
        LocalDate d = dayD();
        long platformRequest = createDraft(as(alice));
        long platformLine = personLine(as(alice), platformRequest, 1, d.plusDays(2), d.plusDays(2), statistics);
        submit(as(alice), platformRequest).andExpect(status().isOk());
        select(as(alice), platformRequest, platformLine, world.dave().id()).andExpect(status().isOk());
        Formed formed = loanOfDave(d, d.plusDays(2));
        long approval = approvalOf(platformLine);

        List<String> outcomes = race(
                as(bob, member -> lifecycle.endLoanEarly(member.actor(), new LoanId(formed.loan()), d.plusDays(1))
                        .endedEarly() ? "ENDED" : "NOT ENDED"),
                as(oscar, member -> lifecycle.decideApproval(member.actor(), new ApprovalId(approval),
                        Decision.APPROVE, "").state().name()));

        assertThat(outcomes.getFirst()).as("the early end").isEqualTo("ENDED");
        assertThat(outcomes.get(1)).as("the approval").isIn("APPROVED", "RuleViolation: NOT_A_CANDIDATE");
        List<Date> ends = jdbc.queryForList("SELECT ends_on FROM loan WHERE member_id = ? ORDER BY starts_on",
                Date.class, world.dave().id());
        if (outcomes.get(1).equals("APPROVED")) {
            assertThat(ends).containsExactly(Date.valueOf(d.plusDays(1)), Date.valueOf(d.plusDays(2)));
            detail(as(alice), platformRequest).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        } else {
            assertThat(ends).containsExactly(Date.valueOf(d.plusDays(1)));
            detail(as(alice), platformRequest).andExpect(jsonPath("$.request.state").value("PENDING"));
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM loan a JOIN loan b ON a.member_id = b.member_id AND a.id < b.id
                WHERE a.member_id = ? AND a.starts_on <= b.ends_on AND b.starts_on <= a.ends_on""", Integer.class,
                world.dave().id())).as("overlapping Loans").isZero();
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    /** A call made as this Member, with their session in the security context. */
    private Callable<String> as(String username, Function<SignedInMember, String> call) {
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

    /** Runs the calls at once, released by one latch; returns per call its outcome or the refusal's code. */
    @SafeVarargs
    private List<String> race(Callable<String>... calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.length);
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
