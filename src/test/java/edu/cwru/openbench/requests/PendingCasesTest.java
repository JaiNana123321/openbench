package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.jobs.DueWorkJob;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Lifecycle test cases of Pending Requests (docs/test-cases, Section "Request Lifecycle") that
 * issue #5 covers, and the Pending part of {@code drf-withdraw}: Holds and held credits while a
 * Required Person line awaits approval, the Objection Window, and its lapse.
 */
class PendingCasesTest extends MatchedWorldTest {

    @Autowired
    private RequestLifecycleService lifecycle;

    @Autowired
    private DueWorkJob dueWorkJob;

    @Test
    @DisplayName("lif-pending-holds: a Request that needs an approval is Pending with Holds and held credits")
    @Verifies("lif-pending-holds")
    void aRequestThatNeedsAnApprovalIsPendingWithHolds() throws Exception {
        LocalDate d = dayD();

        // 1. A1 with M1 (Statistics), L1 (Room A, D 10:00-12:00, 30.00), and L2 (GPU, 4 units, D 10:00-12:00, 60.00).
        A1 a1 = a1(d);
        submit(as(alice), a1.id()).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.lines[0].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[1].line.state").value("HELD"))
                .andExpect(jsonPath("$.lines[1].assignedResources[0].id").value((int) roomA))
                .andExpect(jsonPath("$.lines[2].line.state").value("HELD"));
        assertThat(jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = 'REQUEST_UPDATE'",
                String.class, world.alice().id())).anySatisfy(text -> assertThat(text).contains("is Pending"));

        // 2. Platform's Statement and Account: no Charge; held 90.00; Balance 5000.00; Available 4910.00.
        mvc.perform(get("/api/ledger/statements/{team}/{period}", platform, period).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')]", empty()))
                .andExpect(jsonPath("$.account.held").value(90.00))
                .andExpect(jsonPath("$.account.balance").value(5000.00))
                .andExpect(jsonPath("$.account.available").value(4910.00));
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));

        // 3. Room A on D: 10:00-12:00 on Hold for Platform, A1 (the timeline screen is #11; its rows here).
        assertThat(jdbc.queryForList("""
                SELECT state, team_id, request_id, starts_at, ends_at FROM exclusive_occurrence
                WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED')""", roomA))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("state", "HELD").containsEntry("team_id", platform)
                            .containsEntry("request_id", a1.id());
                    assertThat(((Timestamp) row.get("starts_at")).toInstant()).isEqualTo(at(d, 10));
                    assertThat(((Timestamp) row.get("ends_at")).toInstant()).isEqualTo(at(d, 12));
                });
        // The same through Room A's timeline (issue #11), as Alice, whose Team holds it.
        mvc.perform(get("/api/inventory/resources/{id}/holders", roomA).with(as(alice))
                        .param("from", at(d, 10).toString()).param("to", at(d, 12).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].state").value("HELD"))
                .andExpect(jsonPath("$[0].team.id").value((int) platform))
                .andExpect(jsonPath("$[0].request").value((int) a1.id()));
    }

    @Test
    @DisplayName("lif-held-credits: held credits stop a second submission from exceeding the Balance")
    @Verifies("lif-held-credits")
    void heldCreditsStopConcurrentPendingRequestsFromExceedingTheBalance() throws Exception {
        LocalDate d = dayD();

        // 1. B1: M1 (Java) and L1 (Microscope 1, D 09:00-17:00, 480.00): Pending, 480.00 held, 120.00 available.
        long b1 = createDraft(as(bob));
        personLine(as(bob), b1, 1, d, d.plusDays(1), java);
        line(as(bob), b1, microscope, 1, at(d, 9), at(d, 17), null);
        submit(as(bob), b1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        assertThat(account(research)).containsEntry("held", new BigDecimal("480.00"));
        assertThat(available(research)).isEqualByComparingTo("120.00");

        // 2. B2: L2 (Microscope 1, D+1 09:00-12:00, 180.00): a shortfall of 60.00, and refused.
        long b2 = createDraft(as(bob));
        line(as(bob), b2, microscope, 1, at(d.plusDays(1), 9), at(d.plusDays(1), 12), null);
        mvc.perform(get("/api/requests/{id}/draft", b2).with(as(bob)))
                .andExpect(jsonPath("$.requiredSum").value(180.00))
                .andExpect(jsonPath("$.shortfall").value(60.00));
        submit(as(bob), b2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        // The Research Account never commits more than its Balance.
        assertThat(available(research)).isEqualByComparingTo("120.00");
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    @Test
    @DisplayName("lif-window-start, step 1: submitted on Thursday at t, the Objection Window ends on Monday at t")
    @Verifies(value = "lif-window-start", partial = true)
    void theObjectionWindowStartsAtSubmissionAndLastsTwoWorkingDays() throws Exception {
        LocalDate thursday = LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
        Instant t = thursday.atTime(LocalTime.of(10, 37, 12)).atZone(clock.getZone()).toInstant();
        movableClock.set(t);
        A1 a1 = a1(dayD());

        String body = submit(as(alice), a1.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        Instant monday = thursday.plusDays(4).atTime(LocalTime.of(10, 37, 12)).atZone(clock.getZone()).toInstant();
        assertThat(Instant.parse(JsonPath.read(body, "$.windowEndsAt"))).isEqualTo(monday);
        String shown = detail(as(alice), a1.id()).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(shown, "$.request.windowEndsAt"))).isEqualTo(monday);
    }

    @Test
    @DisplayName("lif-window-lapse: on lapse every Hold is released, the approval escalated, and no Hold is placed again")
    @Verifies("lif-window-lapse")
    void onLapseEveryHoldIsReleased() throws Exception {
        LocalDate d = dayD();
        A1 a1 = a1(d);
        submit(as(alice), a1.id()).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a1.id(), a1.m1(), world.dave().id()).andExpect(status().isOk());
        Instant windowEnd = windowEndOf(a1.id());

        // 1. [clock] The window lapses; the job's due work for A1, called directly: Waiting, nothing held, escalated.
        movableClock.set(windowEnd.plusSeconds(60));
        assertThat(lifecycle.lapsedWindows(clock.instant())).contains(new RequestId(a1.id()));
        lifecycle.onWindowLapse(new RequestId(a1.id()));

        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].line.state").value("EVALUATED"))
                .andExpect(jsonPath("$.lines[2].line.state").value("EVALUATED"))
                .andExpect(jsonPath("$.lines[0].candidates[0].state").value("OUTSTANDING"))
                .andExpect(jsonPath("$.lines[0].candidates[0].escalated").value(true));
        assertThat(liveRowsOf(a1.id())).isZero();
        assertThat(account(platform)).containsEntry("held", new BigDecimal("0.00"));
        assertThat(available(platform)).isEqualByComparingTo("5000.00");
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));

        // The lapse is idempotent: a second run changes and records nothing.
        int events = eventsOf(a1.id());
        lifecycle.onWindowLapse(new RequestId(a1.id()));
        assertThat(eventsOf(a1.id())).isEqualTo(events);
        assertThat(lifecycle.lapsedWindows(clock.instant())).doesNotContain(new RequestId(a1.id()));

        // 2. Oscar's O1 (Room A, D 10:00-12:00) is Allocated: the released Hold no longer blocks Room A.
        long o1 = createDraft(as(oscar));
        line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(oscar), o1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));

        // 3. A1 is Waiting and holds nothing; Room A shows O1 only. No Hold was placed for A1 after the lapse.
        detail(as(alice), a1.id()).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(liveRowsOf(a1.id())).isZero();
        assertThat(jdbc.queryForList("""
                SELECT request_id FROM exclusive_occurrence WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED')""",
                Long.class, roomA)).containsExactly(o1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM request_event WHERE request_id = ? AND to_state = 'HELD' AND at >= ?""",
                Integer.class, a1.id(), Timestamp.from(windowEnd))).isEqualTo(0);

        // After the window ended, the lending Manager can no longer decide.
        decide(as(oscar), approvalOf(a1.m1()), "APPROVE", "Late")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_LAPSED"));
    }

    @Test
    @DisplayName("Lifecycle.Window.Start: DueWorkJob lapses an Objection Window that ended, within its run")
    @Verifies(value = "lif-window-lapse", partial = true)
    void theDueWorkJobLapsesTheWindow() throws Exception {
        A1 a1 = a1(dayD());
        submit(as(alice), a1.id()).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a1.id(), a1.m1(), world.dave().id()).andExpect(status().isOk());

        movableClock.set(windowEndOf(a1.id()).minusSeconds(60));
        dueWorkJob.run();
        detail(as(alice), a1.id()).andExpect(jsonPath("$.request.state").value("PENDING"));

        movableClock.set(windowEndOf(a1.id()));
        dueWorkJob.run();
        detail(as(alice), a1.id())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].candidates[0].escalated").value(true));
        assertThat(liveRowsOf(a1.id())).isZero();
    }

    @Test
    @DisplayName("drf-withdraw, the Pending part: withdrawing releases the Holds and held credits and cancels the approvals")
    @Verifies(value = "drf-withdraw", partial = true)
    void withdrawingAPendingRequestReleasesEverythingHeld() throws Exception {
        LocalDate d = dayD();
        long b3 = createDraft(as(bob));
        long java1 = personLine(as(bob), b3, 1, d, d.plusDays(1), java);
        line(as(bob), b3, microscope, 1, at(d, 9), at(d, 17), null);
        submit(as(bob), b3).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(bob), b3, java1, world.ben().id()).andExpect(status().isOk());
        int transactions = transactionsOf(research);

        // 3. Before: 120.00 available (480.00 held). After: Withdrawn, 600.00 available, no Transaction posted.
        assertThat(available(research)).isEqualByComparingTo("120.00");
        withdraw(as(bob), b3).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WITHDRAWN"));
        assertThat(available(research)).isEqualByComparingTo("600.00");
        assertThat(transactionsOf(research)).isEqualTo(transactions);
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));

        // 4. Microscope 1 is free on D 09:00-17:00: the Hold was released.
        assertThat(liveRowsOf(b3)).isZero();
        assertThat(jdbc.queryForList("SELECT state FROM exclusive_occurrence WHERE request_id = ?", String.class, b3))
                .containsOnly("EXPIRED");

        // No Hold, held credit, or open approval of B3 remains.
        assertThat(jdbc.queryForObject("SELECT state FROM approval WHERE id = ?", String.class, approvalOf(java1)))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM held_credit h JOIN request_line l ON l.id = h.line_id WHERE l.request_id = ?""",
                Integer.class, b3)).isZero();
        detail(as(bob), b3)
                .andExpect(jsonPath("$.lines[0].line.state").value("CANCELED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("CANCELED"));
        approvals(as(alice)).andExpect(jsonPath("$", empty()));
    }

    // ---------------------------------------------------------------- the Request A1 of lif-pending-holds

    /** A1's Draft and its Person line M1. */
    private record A1(long id, long m1) {}

    /** Alice's A1: M1 (Statistics, 1, D to D+1), L1 (Room A, D 10:00-12:00), L2 (GPU, 4 units, D 10:00-12:00). */
    private A1 a1(LocalDate d) throws Exception {
        long draft = createDraft(as(alice));
        long m1 = personLine(as(alice), draft, 1, d, d.plusDays(1), statistics);
        line(as(alice), draft, room, 1, at(d, 10), at(d, 12), roomA);
        line(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null);
        return new A1(draft, m1);
    }

    private Instant windowEndOf(long request) {
        return jdbc.queryForObject("SELECT window_ends_at FROM request WHERE id = ?", Timestamp.class, request)
                .toInstant();
    }

    /** The Request's HELD or ALLOCATED Exclusive and Metered rows. */
    private int liveRowsOf(long request) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM exclusive_occurrence WHERE request_id = :r AND state IN ('HELD', 'ALLOCATED'))
                     + (SELECT count(*) FROM metered_allocation WHERE request_id = :r AND state IN ('HELD', 'ALLOCATED'))"""
                .replace(":r", Long.toString(request)), Integer.class);
    }

    private int eventsOf(long request) {
        return jdbc.queryForObject("SELECT count(*) FROM request_event WHERE request_id = ?", Integer.class, request);
    }

    private int transactionsOf(long team) {
        return jdbc.queryForObject("SELECT count(*) FROM transaction WHERE team_id = ?", Integer.class, team);
    }
}
