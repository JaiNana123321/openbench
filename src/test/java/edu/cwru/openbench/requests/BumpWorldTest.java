package edu.cwru.openbench.requests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base of the Bump tests (issue #9): the Matched test world, Alice (Platform, 5000.00), Bob
 * (Research, 600.00), and Oscar (Operations, 1000.00), with the test world's bump cutoff of 24 hours
 * on both Exclusive types and its Refund Schedule (75% at 168 hours or more, 25% below), which a
 * Bump's full Refund ignores. The Clock is fixed at the start of each test and moves one second before
 * each submission, so the submission order the Bump plan breaks ties by never depends on timing.
 */
abstract class BumpWorldTest extends MatchedWorldTest {

    @BeforeEach
    void setTheBumpCutoffs() {
        jdbc.update("UPDATE type_pricing SET bump_cutoff_hours = 24 WHERE type_id IN (?, ?)", room, microscope);
        for (long type : List.of(room, microscope, gpu)) {
            jdbc.update("INSERT INTO refund_step (type_id, min_lead_hours, fraction) VALUES (?, 0, 0.25), (?, 168, 0.75)",
                    type, type);
        }
        movableClock.set(clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    /** Moves the Clock one second on: the next submission is later than every earlier one. */
    protected void nextSecond() {
        movableClock.set(clock.instant().plusSeconds(1));
    }

    /** Submits a Request of one line of this type and returns {request, line}; it is Allocated if the line fits. */
    protected long[] submitted(String manager, long type, Instant start, Instant end, Long named, boolean priority)
            throws Exception {
        long draft = createDraft(as(manager));
        long line = line(as(manager), draft, type, 1, start, end, named);
        if (priority) {
            setPriority(as(manager), draft, line, true).andExpect(status().isOk());
        }
        nextSecond();
        submit(as(manager), draft).andExpect(status().isOk());
        return new long[] {draft, line};
    }

    protected ResultActions setPriority(RequestPostProcessor caller, long draft, long line, boolean priority)
            throws Exception {
        return mvc.perform(put("/api/requests/{id}/lines/{line}/priority", draft, line).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\": " + priority + "}"));
    }

    /** Test case day H: the first whole hour at least two hours from now, inside the 24-hour bump cutoff. */
    protected Instant hourH() {
        return clock.instant().plus(2, ChronoUnit.HOURS).plus(59, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.HOURS);
    }

    protected ResultActions timeline(String username, long resource, Instant from, Instant to) throws Exception {
        return mvc.perform(get("/api/inventory/resources/{id}/holders", resource).with(as(username))
                .param("from", from.toString()).param("to", to.toString()));
    }

    protected ResultActions statement(String manager, long team) throws Exception {
        return mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(as(manager)));
    }

    /** The line's Refunds, oldest first. */
    protected List<String> refundsOf(long line) {
        return jdbc.queryForList("SELECT amount::text FROM transaction WHERE kind = 'REFUND' AND line_id = ? ORDER BY id",
                String.class, line);
    }

    /** The states of the line's occurrence rows, by id. */
    protected List<String> rowStates(long line) {
        return jdbc.queryForList("SELECT state FROM exclusive_occurrence WHERE line_id = ? ORDER BY id", String.class,
                line);
    }

    /** The Member's notification texts of this kind, oldest first. */
    protected List<String> noticesOf(long member, String kind) {
        return jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = ? ORDER BY id",
                String.class, member, kind);
    }

    /** The line's waitlist entry as "bumped/priority", or nothing when it does not wait. */
    protected List<String> waitlistEntryOf(long line) {
        return jdbc.queryForList("SELECT bumped || '/' || priority FROM waitlist_entry WHERE line_id = ?", String.class,
                line);
    }
}
