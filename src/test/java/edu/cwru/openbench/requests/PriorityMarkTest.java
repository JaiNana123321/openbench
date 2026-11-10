package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The Priority mark on Draft lines (SRS Exclusive.Priority.Bump, the drafting part; Ledger.Price.Formula, the
 * premium): PUT /api/requests/{id}/lines/{lineId}/priority, its refusals, and what a marked line carries through
 * submission. Bumps themselves come later (#9).
 */
class PriorityMarkTest extends DemoWorldTest {

    @Test
    @DisplayName("Ledger.Price.Premium: a Priority room line D 10:00-12:00 quotes 60.00 (Q4), and its Charge at submission is 60.00")
    void chargesThePriorityPremium() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long line = line(as(alice), draft, room, 1, at(d, 10), at(d, 12), null);

        setPriority(as(alice), draft, line, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(line))
                .andExpect(jsonPath("$.priority").value(true))
                .andExpect(jsonPath("$.quote").value(60.00));
        submit(as(alice), draft).andExpect(jsonPath("$.state").value("ALLOCATED"));

        detail(as(alice), draft)
                .andExpect(jsonPath("$.lines[0].line.priority").value(true))
                .andExpect(jsonPath("$.lines[0].line.quote").value(60.00));
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, line)).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("Unmarking Priority restores the standard Quote; unmarking a Metered line is allowed")
    void unmarksALine() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long room1 = line(as(alice), draft, room, 1, at(d, 10), at(d, 12), null);
        long gpu1 = line(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null);

        setPriority(as(alice), draft, room1, true).andExpect(jsonPath("$.quote").value(60.00));
        setPriority(as(alice), draft, room1, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(false))
                .andExpect(jsonPath("$.quote").value(30.00));
        setPriority(as(alice), draft, gpu1, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(false));
    }

    @Test
    @DisplayName("A Metered line cannot be marked Priority: 422 PRIORITY_NOT_OFFERED, and the line stays standard")
    void refusesPriorityOnAMeteredLine() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long gpu1 = line(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null);

        setPriority(as(alice), draft, gpu1, true)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRIORITY_NOT_OFFERED"));
        assertThat(jdbc.queryForObject("SELECT priority FROM request_line WHERE id = ?", Boolean.class, gpu1)).isFalse();
    }

    @Test
    @DisplayName("The mark changes only in a Draft (409 WRONG_STATE), only by its Team's Manager (404), and needs a body (400)")
    void refusesTheMarkOutsideTheOwnersDraft() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long line = line(as(alice), draft, room, 1, at(d, 10), at(d, 12), null);

        setPriority(as(bob), draft, line, true).andExpect(status().isNotFound());
        mvc.perform(put("/api/requests/{id}/lines/{line}/priority", draft, line).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        submit(as(alice), draft).andExpect(jsonPath("$.state").value("ALLOCATED"));
        setPriority(as(alice), draft, line, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
        assertThat(jdbc.queryForObject("SELECT priority FROM request_line WHERE id = ?", Boolean.class, line)).isFalse();
    }

    @Test
    @DisplayName("A split part of a Priority line keeps the mark; a waiting Priority line joins the waitlist marked Priority")
    void carriesTheMarkThroughSplitAndTheWaitlist() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long rooms = line(as(alice), draft, room, 2, at(d, 10), at(d, 12), null);
        setPriority(as(alice), draft, rooms, true).andExpect(jsonPath("$.quote").value(120.00));

        mvc.perform(post("/api/requests/{id}/lines/{line}/split", draft, rooms).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value(true))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.quote").value(60.00));

        // Both rooms taken on D: bob's Priority line waits, marked Priority on the waitlist.
        submit(as(alice), draft).andExpect(jsonPath("$.state").value("ALLOCATED"));
        long waiting = createDraft(as(bob));
        long bobLine = line(as(bob), waiting, room, 1, at(d, 10), at(d, 12), null);
        setPriority(as(bob), waiting, bobLine, true).andExpect(status().isOk());
        submit(as(bob), waiting).andExpect(jsonPath("$.state").value("WAITING"));
        assertThat(jdbc.queryForObject("SELECT priority FROM waitlist_entry WHERE line_id = ?", Boolean.class, bobLine))
                .isTrue();
    }

    private ResultActions setPriority(RequestPostProcessor caller, long draft, long line, boolean priority)
            throws Exception {
        return mvc.perform(put("/api/requests/{id}/lines/{line}/priority", draft, line).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\": " + priority + "}"));
    }
}
