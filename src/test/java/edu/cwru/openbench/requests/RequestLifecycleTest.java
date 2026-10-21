package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Submission, withdrawal, and the detail through the API, one contract per test: each outcome and
 * each refusal code, the scope and role checks, and all or nothing when a Required line misfits.
 */
class RequestLifecycleTest extends DemoWorldTest {

    @Test
    @DisplayName("Lifecycle.Allocate: every Required line fits, so the lines are allocated and charged their locked Quotes")
    void allocatesARequestWhoseRequiredLinesFit() throws Exception {
        long request = createDraft(as(alice));
        long roomLine = line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        long gpuLine = line(as(alice), request, gpu, 4, tuesdayAt(9), thursdayAt(9), null);

        submit(as(alice), request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(request))
                .andExpect(jsonPath("$.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty());

        detail(as(alice), request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.id").value(roomLine))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(30.00))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].id").value(roomA))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].name").value(roomAName))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty())
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.quote").value(1120.00))
                .andExpect(jsonPath("$.lines[1].assignedResources.length()").value(0))
                .andExpect(jsonPath("$.history[0].toState").value("DRAFT"))
                .andExpect(jsonPath("$.history[1].toState").value("SUBMITTED"))
                .andExpect(jsonPath("$.history[1].actorName").value(alice))
                .andExpect(jsonPath("$.history[-1].fromState").value("SUBMITTED"))
                .andExpect(jsonPath("$.history[-1].toState").value("ALLOCATED"));

        assertThat(jdbc.queryForList("SELECT line_id || ':' || amount FROM transaction WHERE kind = 'CHARGE' AND team_id = ? ORDER BY line_id",
                String.class, platform)).containsExactly(roomLine + ":30.00", gpuLine + ":1120.00");
        assertThat(jdbc.queryForList("SELECT state FROM exclusive_occurrence WHERE line_id = ?", String.class, roomLine))
                .containsExactly("ALLOCATED");
        assertThat(jdbc.queryForList("SELECT state FROM metered_allocation WHERE line_id = ?", String.class, gpuLine))
                .containsExactly("ALLOCATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE team_id = ?", Integer.class, platform)).isZero();
        assertThat(available(platform)).isEqualByComparingTo("3850.00");
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
        assertThat(lineEvents(request)).containsExactly(
                roomLine + ":DRAFT>ALLOCATED", gpuLine + ":DRAFT>ALLOCATED");
    }

    @Test
    @DisplayName("Draft.Submit.Lock: the Quotes are locked at submission and do not follow a later price change")
    void locksTheQuotes() throws Exception {
        long request = createDraft(as(alice));
        line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        submit(as(alice), request).andExpect(status().isOk());

        jdbc.update("UPDATE type_pricing SET unit_price = 99.00 WHERE type_id = ?", room);

        detail(as(alice), request).andExpect(jsonPath("$.lines[0].line.quote").value(30.00));
        mvc.perform(get("/api/requests/{id}/draft", request).with(as(alice)))
                .andExpect(jsonPath("$.lines[0].quote").value(30.00));
    }

    @Test
    @DisplayName("INSUFFICIENT_BALANCE: a Request costing more than the Available Balance is refused with the shortfall and stays a Draft")
    void refusesARequestOverTheAvailableBalance() throws Exception {
        long request = createDraft(as(bob));
        line(as(bob), request, microscope, 1, tuesdayAt(9), tuesdayAt(17), null);
        line(as(bob), request, room, 1, tuesdayAt(9), tuesdayAt(17), roomB);
        line(as(bob), request, gpu, 1, tuesdayAt(9), tuesdayAt(10), null);

        submit(as(bob), request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.required").value(607.50))
                .andExpect(jsonPath("$.available").value(600.00))
                .andExpect(jsonPath("$.shortfall").value(7.50));

        detail(as(bob), request)
                .andExpect(jsonPath("$.request.state").value("DRAFT"))
                .andExpect(jsonPath("$.request.submittedAt").isEmpty())
                .andExpect(jsonPath("$.history.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_line WHERE request_id = ? AND locked_quote IS NOT NULL",
                Integer.class, request)).isZero();
        assertThat(available(research)).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("Exclusive.Waitlist.Join: Room A at an overlapping time waits at position 1, with the reason, and nothing is held")
    void waitsForATakenRoom() throws Exception {
        long allocated = createDraft(as(alice));
        line(as(alice), allocated, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        submit(as(alice), allocated).andExpect(jsonPath("$.state").value("ALLOCATED"));

        long request = createDraft(as(bob));
        long roomLine = line(as(bob), request, room, 1, tuesdayAt(15), tuesdayAt(17), roomA);
        submit(as(bob), request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"));

        detail(as(bob), request)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(30.00))
                .andExpect(jsonPath("$.lines[0].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[0].reasonMessage").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1))
                .andExpect(jsonPath("$.lines[0].assignedResources.length()").value(0))
                .andExpect(jsonPath("$.history[-1].toState").value("WAITING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id = ?", Integer.class, roomLine))
                .isEqualTo(1);
        assertNothingHeldOrCharged(roomLine);
        assertThat(available(research)).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("Metered.Pool.Check: 10 GPU units exceed Research's Quota of 8, so the line waits with QUOTA_EXCEEDED")
    void waitsOverTheQuota() throws Exception {
        long request = createDraft(as(bob));
        line(as(bob), request, gpu, 10, tuesdayAt(10), tuesdayAt(14), null);

        submit(as(bob), request).andExpect(jsonPath("$.state").value("WAITING"));

        detail(as(bob), request)
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(300.00))
                .andExpect(jsonPath("$.lines[0].reason").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
    }

    @Test
    @DisplayName("QA-atomic: when one Required line misfits, the fitting one is not held, allocated, or charged either")
    void allocatesNothingWhenARequiredLineMisfits() throws Exception {
        long allocated = createDraft(as(alice));
        line(as(alice), allocated, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        submit(as(alice), allocated).andExpect(jsonPath("$.state").value("ALLOCATED"));

        long request = createDraft(as(bob));
        long gpuLine = line(as(bob), request, gpu, 2, tuesdayAt(10), tuesdayAt(12), null);
        long roomLine = line(as(bob), request, room, 1, tuesdayAt(14), tuesdayAt(15), roomA);
        submit(as(bob), request).andExpect(jsonPath("$.state").value("WAITING"));

        detail(as(bob), request)
                .andExpect(jsonPath("$.lines[0].line.state").value("EVALUATED"))
                .andExpect(jsonPath("$.lines[0].reason").isEmpty())
                .andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty())
                .andExpect(jsonPath("$.lines[1].line.state").value("WAITING"));
        assertNothingHeldOrCharged(gpuLine);
        assertNothingHeldOrCharged(roomLine);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id = ?", Integer.class, gpuLine))
                .as("a fitting line does not wait").isZero();
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
        assertThat(available(research)).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("Draft.Line.Required: an Optional line that does not fit is Dropped with its reason; the Request is still Allocated")
    void dropsAnUnfitOptionalLine() throws Exception {
        long allocated = createDraft(as(alice));
        line(as(alice), allocated, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        submit(as(alice), allocated).andExpect(jsonPath("$.state").value("ALLOCATED"));

        long request = createDraft(as(bob));
        long gpuLine = line(as(bob), request, gpu, 2, tuesdayAt(10), tuesdayAt(12), null);
        long roomLine = line(as(bob), request, room, 1, tuesdayAt(14), tuesdayAt(15), roomA);
        markOptional(as(bob), request, roomLine);
        submit(as(bob), request).andExpect(jsonPath("$.state").value("ALLOCATED"));

        detail(as(bob), request)
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("DROPPED"))
                .andExpect(jsonPath("$.lines[1].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[1].waitlistPosition").isEmpty());
        assertNothingHeldOrCharged(roomLine);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id = ?", Integer.class, roomLine))
                .as("an Optional line never waits").isZero();
        assertThat(jdbc.queryForList("SELECT amount FROM transaction WHERE line_id = ?", BigDecimal.class, gpuLine))
                .containsExactly(new BigDecimal("30.00"));
    }

    @Test
    @DisplayName("Lines of one Request that compete for one room: the second waits and the first's Hold is cancelled")
    void waitsWhenItsOwnLinesCompete() throws Exception {
        long request = createDraft(as(alice));
        long first = line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        long second = line(as(alice), request, room, 1, tuesdayAt(15), tuesdayAt(17), roomA);

        submit(as(alice), request).andExpect(jsonPath("$.state").value("WAITING"));

        detail(as(alice), request)
                .andExpect(jsonPath("$.lines[0].line.state").value("EVALUATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].reason").value("NO_FREE_INSTANCE"));
        assertThat(jdbc.queryForList("SELECT state FROM exclusive_occurrence WHERE line_id = ?", String.class, first))
                .containsExactly("EXPIRED");
        assertNothingHeldOrCharged(second);
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
        assertThat(available(platform)).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("Draft.Line.Validate at submission: a window that has started since drafting is refused, and the Request stays a Draft")
    void checksTheLinesAgainAtSubmission() throws Exception {
        long request = createDraft(as(alice));
        long roomLine = line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), null);
        jdbc.update("UPDATE request_line SET starts_at = date_trunc('hour', now()) - interval '1 hour' WHERE id = ?", roomLine);

        submit(as(alice), request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_NOT_FUTURE"));
        detail(as(alice), request).andExpect(jsonPath("$.request.state").value("DRAFT"));
    }

    @Test
    @DisplayName("EMPTY_REQUEST: a Draft without lines cannot be submitted")
    void refusesAnEmptyDraft() throws Exception {
        submit(as(alice), createDraft(as(alice)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPTY_REQUEST"));
    }

    @Test
    @DisplayName("WRONG_STATE: a Request that is not a Draft cannot be submitted again, and an Allocated one cannot be withdrawn")
    void refusesTransitionsTheStateDoesNotAllow() throws Exception {
        long request = createDraft(as(alice));
        line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), null);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("ALLOCATED"));

        submit(as(alice), request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
        withdraw(as(alice), request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE kind = 'CHARGE' AND team_id = ?",
                Integer.class, platform)).as("charged once").isEqualTo(1);
    }

    @Test
    @DisplayName("Draft.Create: withdrawing a Waiting Request cancels its lines and takes them off the waitlist")
    void withdrawsAWaitingRequest() throws Exception {
        long allocated = createDraft(as(alice));
        line(as(alice), allocated, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        submit(as(alice), allocated);
        long request = createDraft(as(bob));
        long roomLine = line(as(bob), request, room, 1, tuesdayAt(15), tuesdayAt(17), roomA);
        long gpuLine = line(as(bob), request, gpu, 2, tuesdayAt(10), tuesdayAt(12), null);
        submit(as(bob), request).andExpect(jsonPath("$.state").value("WAITING"));

        withdraw(as(bob), request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WITHDRAWN"));

        detail(as(bob), request)
                .andExpect(jsonPath("$.lines[0].line.state").value("CANCELED"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty())
                .andExpect(jsonPath("$.lines[1].line.state").value("CANCELED"))
                .andExpect(jsonPath("$.history[-1].fromState").value("WAITING"))
                .andExpect(jsonPath("$.history[-1].toState").value("WITHDRAWN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id IN (?, ?)",
                Integer.class, roomLine, gpuLine)).isZero();
        withdraw(as(bob), request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_STATE"));
    }

    @Test
    @DisplayName("Draft.Create: a Draft can be withdrawn, after which it can be neither edited nor submitted")
    void withdrawsADraft() throws Exception {
        long request = createDraft(as(alice));
        line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), null);

        withdraw(as(alice), request).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WITHDRAWN"));

        detail(as(alice), request).andExpect(jsonPath("$.lines[0].line.state").value("CANCELED"));
        submit(as(alice), request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_STATE"));
        addLine(as(alice), request, room, 1, tuesdayAt(9), tuesdayAt(10), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
    }

    @Test
    @DisplayName("Another Team's Request is not found: submit, withdraw, and the detail all answer 404")
    void hidesAnotherTeamsRequest() throws Exception {
        long request = createDraft(as(alice));
        line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), null);

        submit(as(bob), request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        withdraw(as(bob), request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        detail(as(bob), request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        detail(as(alice), request).andExpect(jsonPath("$.request.state").value("DRAFT"));
    }

    @Test
    @DisplayName("Without MANAGER a Member cannot submit or withdraw (403); a Capacity Planner may read any Team's detail")
    void requiresTheManagerRole() throws Exception {
        long request = createDraft(as(alice));
        line(as(alice), request, room, 1, tuesdayAt(14), tuesdayAt(16), null);

        submit(as(ben), request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        withdraw(as(ben), request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        detail(as(ben), request).andExpect(status().isForbidden());
        submit(as(erin), request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        detail(as(erin), request).andExpect(status().isOk()).andExpect(jsonPath("$.request.state").value("DRAFT"));
    }

    @Test
    @DisplayName("GET /api/requests/lines: the Statement's lookup returns the caller's own lines and leaves out other Teams'")
    void looksUpLinesInScope() throws Exception {
        long ours = createDraft(as(alice));
        long ourLine = line(as(alice), ours, room, 1, tuesdayAt(14), tuesdayAt(16), null);
        long theirs = createDraft(as(bob));
        long theirLine = line(as(bob), theirs, room, 1, tuesdayAt(14), tuesdayAt(16), null);

        mvc.perform(get("/api/requests/lines").param("ids", ourLine + "," + theirLine).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ourLine))
                .andExpect(jsonPath("$[0].requestId").value(ours));
        mvc.perform(get("/api/requests/lines").param("ids", ourLine + "," + theirLine).with(as(erin)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private void assertNothingHeldOrCharged(long line) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ? AND state IN ('HELD', 'ALLOCATED')",
                Integer.class, line)).as("live occurrences of line %d", line).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM metered_allocation WHERE line_id = ?", Integer.class, line))
                .as("metered rows of line %d", line).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE line_id = ?", Integer.class, line))
                .as("held credits of line %d", line).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE line_id = ?", Integer.class, line))
                .as("Charges of line %d", line).isZero();
    }

    /** The Request's line events as "line:from>to", oldest first. */
    private List<String> lineEvents(long request) {
        return jdbc.queryForList("""
                SELECT line_id || ':' || from_state || '>' || to_state FROM request_event
                WHERE request_id = ? AND line_id IS NOT NULL ORDER BY id""", String.class, request);
    }
}
