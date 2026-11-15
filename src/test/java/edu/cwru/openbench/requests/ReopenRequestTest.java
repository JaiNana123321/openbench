package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.RequestId;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Waiting-edit acceptance and refusals, through the API on PostgreSQL. */
class ReopenRequestTest extends MatchedWorldTest {

    @Autowired
    private RequestLifecycleService lifecycle;

    @Test
    @Verifies("drf-edit-waiting")
    void editingWaitingReturnsToDraftAndRequotesAtCurrentPrices() throws Exception {
        LocalDate d = dayD();
        long blocker = createDraft(as(alice));
        long blockedLine = line(as(alice), blocker, room, 2, at(d, 10), at(d, 12), null);
        submit(as(alice), blocker).andExpect(jsonPath("$.state").value("ALLOCATED"));
        long b1 = createDraft(as(bob));
        long l1 = line(as(bob), b1, room, 1, at(d, 10), at(d, 12), null);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
        detail(as(bob), b1).andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));

        // 1. Erin changes the Unit Price via Settings; the submitted Quote stays locked.
        mvc.perform(put("/api/settings/unit_price.{type}", room).with(as(erin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":20.00}"))
                .andExpect(status().isOk());
        detail(as(bob), b1).andExpect(jsonPath("$.lines[0].line.quote").value(30.00));

        // 2. Edit: no settlement, no submission time or locked Quote, and no waitlist entry.
        int transactions = transactionsOf(research);
        reopen(as(bob), b1).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.submittedAt").isEmpty());
        assertThat(transactionsOf(research)).isEqualTo(transactions);
        assertThat(jdbc.queryForObject("SELECT locked_quote FROM request_line WHERE id = ?", BigDecimal.class, l1))
                .isNull();
        assertThat(waitlistEntries(l1)).isZero();
        detail(as(bob), b1).andExpect(jsonPath("$.lines[0].line.state").value("DRAFT"))
                .andExpect(jsonPath("$.history[?(@.fromState == 'WAITING')].toState", hasItem("DRAFT")));
        assertThat(jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = 'REQUEST_UPDATE'",
                String.class, world.bob().id())).anySatisfy(text -> assertThat(text).contains("reopened", "Draft"));

        // A later fill must not pick the reopened line.
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", blocker, blockedLine).with(as(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"WHOLE\"}"))
                .andExpect(status().isOk());
        detail(as(bob), b1).andExpect(jsonPath("$.request.state").value("DRAFT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ?", Integer.class, l1))
                .isZero();

        // 3. D 14:00-16:00 is inside the weekday 1.5 band: 20 * 2 * 1.5 = 60.
        mvc.perform(put("/api/requests/{id}/lines/{line}", b1, l1).with(as(bob))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1,\"start\":\"" + at(d, 14) + "\",\"end\":\"" + at(d, 16) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quote").value(60.00));
        // 4. Resubmit: that Quote is locked and charged.
        submit(as(bob), b1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(bob), b1).andExpect(jsonPath("$.lines[0].line.quote").value(60.00));
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE line_id = ? AND kind = 'CHARGE'",
                BigDecimal.class, l1)).isEqualByComparingTo("60.00");
        assertThat(waitlistEntries(l1)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = RequestState.class, names = {"DRAFT", "SUBMITTED", "PENDING", "ALLOCATED", "REFUSED", "WITHDRAWN"})
    void refusesEveryStateExceptWaiting(RequestState state) throws Exception {
        long request = createDraft(as(alice));
        jdbc.update("UPDATE request SET state = ? WHERE id = ?", state.name(), request);
        reopen(as(alice), request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
        assertThat(jdbc.queryForObject("SELECT state FROM request WHERE id = ?", String.class, request))
                .isEqualTo(state.name());
    }

    @Test
    void anotherTeamIsNotFoundAndAPlainMemberIsForbidden() throws Exception {
        long request = createDraft(as(alice));
        reopen(as(bob), request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // Even a Manager who also has Planner visibility cannot reopen another Team's Request.
        jdbc.update("INSERT INTO member_role (member_id, team_id, role) VALUES (?, ?, 'PLANNER')", world.bob().id(), research);
        reopen(as(bob), request).andExpect(status().isNotFound());
        reopen(as(ben), request).andExpect(status().isForbidden());
    }

    @Test
    void refusesABumpedWaitingRequestThatStillHasAChargedAllocation() throws Exception {
        LocalDate d = dayD();
        long request = createDraft(as(alice));
        long bumped = line(as(alice), request, room, 1, at(d, 10), at(d, 12), roomA);
        long retained = line(as(alice), request, room, 1, at(d, 10), at(d, 12), roomB);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("ALLOCATED"));
        long priority = createDraft(as(bob));
        long priorityLine = line(as(bob), priority, room, 1, at(d, 10), at(d, 12), roomA);
        mvc.perform(put("/api/requests/{id}/lines/{line}/priority", priority, priorityLine).with(as(bob))
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":true}"))
                .andExpect(status().isOk());
        submit(as(bob), priority).andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(alice), request).andExpect(jsonPath("$.request.state").value("WAITING"));
        int transactions = transactionsOf(platform);
        reopen(as(alice), request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"))
                .andExpect(jsonPath("$.detail").value("Release its allocated lines first"));
        assertThat(transactionsOf(platform)).isEqualTo(transactions);
        assertThat(waitlistEntries(bumped)).isOne();
        assertThat(jdbc.queryForObject("SELECT state FROM request_line WHERE id = ?", String.class, retained))
                .isEqualTo("ALLOCATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ? AND state = 'ALLOCATED'",
                Integer.class, retained)).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTLY_RELEASED", "LOANED"})
    void refusesOtherLiveAllocationStates(String state) throws Exception {
        long request = createDraft(as(alice));
        long line = line(as(alice), request, room, 1, at(dayD(), 10), at(dayD(), 12), null);
        jdbc.update("UPDATE request SET state = 'WAITING' WHERE id = ?", request);
        jdbc.update("UPDATE request_line SET state = ? WHERE id = ?", state, line);
        reopen(as(alice), request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
    }

    @Test
    void cancelsOutstandingAndApprovedApprovalsAndKeepsTheElapsedWindowOnResubmission() throws Exception {
        LocalDate d = dayD();
        long request = createDraft(as(alice));
        long people = personLine(as(alice), request, 2, d, d.plusDays(1), statistics);
        long roomLine = line(as(alice), request, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), request, people, world.rita().id(), world.dave().id()).andExpect(status().isOk());
        long approval = jdbc.queryForObject("SELECT a.id FROM approval a JOIN candidate c ON c.id = a.candidate_id WHERE c.line_id = ? AND c.member_id = ?",
                Long.class, people, world.rita().id());
        decide(as(bob), approval, "APPROVE", null).andExpect(status().isOk());
        Instant deadline = jdbc.queryForObject("SELECT window_ends_at FROM request WHERE id = ?", Timestamp.class, request).toInstant();
        movableClock.set(deadline.plusSeconds(1));
        lifecycle.onWindowLapse(new RequestId(request));
        int transactions = transactionsOf(platform);
        reopen(as(alice), request).andExpect(status().isOk())
                .andExpect(jsonPath("$.windowEndsAt").value(deadline.atZone(clock.getZone()).toOffsetDateTime().toString()));
        assertThat(jdbc.queryForList("SELECT a.state FROM approval a JOIN candidate c ON c.id = a.candidate_id WHERE c.line_id = ?",
                String.class, people)).containsExactly("CANCELED", "CANCELED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE line_id = ?", Integer.class, roomLine)).isZero();
        assertThat(transactionsOf(platform)).isEqualTo(transactions);
        assertThat(jdbc.queryForObject("SELECT decided_by FROM approval WHERE id = ?", Long.class, approval))
                .isEqualTo(world.bob().id());
        assertThat(jdbc.queryForObject("SELECT decided_at FROM approval WHERE id = ?", Timestamp.class, approval))
                .isNotNull();
        submit(as(alice), request).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.windowEndsAt").value(deadline.atZone(clock.getZone()).toOffsetDateTime().toString()));
        select(as(alice), request, people, world.rita().id(), world.dave().id()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval a JOIN candidate c ON c.id = a.candidate_id WHERE c.line_id = ? AND a.state = 'OUTSTANDING' AND a.escalated",
                Integer.class, people)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ? AND state = 'HELD'",
                Integer.class, roomLine)).isZero();
    }

    @Test
    void releasesLingeringHoldsAndCreditsAndOffersTheFreedCapacity() throws Exception {
        LocalDate d = dayD();
        long request = createDraft(as(alice));
        personLine(as(alice), request, 1, d, d, statistics);
        long held = line(as(alice), request, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("PENDING"));
        long waiting = createDraft(as(bob));
        line(as(bob), waiting, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(bob), waiting).andExpect(jsonPath("$.state").value("WAITING"));
        // Defensive fixture: Waiting normally has already released its Holds.
        jdbc.update("UPDATE request SET state = 'WAITING' WHERE id = ?", request);
        reopen(as(alice), request).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE line_id = ?", Integer.class, held)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM exclusive_occurrence WHERE line_id = ?", String.class, held))
                .isEqualTo("EXPIRED");
        detail(as(bob), waiting).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        assertThat(jdbc.queryForObject("SELECT window_ends_at FROM request WHERE id = ?", Timestamp.class, request))
                .isNotNull();
        submit(as(alice), request).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void resubmissionRejoinsBothWaitlistsAtTheTailAndRestoresDroppedLinesToDraft() throws Exception {
        LocalDate d = dayD();
        long blocker = createDraft(as(alice));
        line(as(alice), blocker, room, 2, at(d, 10), at(d, 12), null);
        line(as(alice), blocker, gpu, 16, at(d, 10), at(d, 12), null);
        line(as(alice), blocker, microscope, 1, at(d, 10), at(d, 12), null);
        submit(as(alice), blocker).andExpect(jsonPath("$.state").value("ALLOCATED"));
        long first = createDraft(as(bob));
        long roomLine = line(as(bob), first, room, 1, at(d, 10), at(d, 12), null);
        long gpuLine = line(as(bob), first, gpu, 1, at(d, 10), at(d, 12), null);
        long optional = line(as(bob), first, microscope, 1, at(d, 10), at(d, 12), null);
        markOptional(as(bob), first, optional);
        submit(as(bob), first).andExpect(jsonPath("$.state").value("WAITING"));
        long second = createDraft(as(alice));
        line(as(alice), second, room, 1, at(d, 10), at(d, 12), null);
        line(as(alice), second, gpu, 1, at(d, 10), at(d, 12), null);
        submit(as(alice), second).andExpect(jsonPath("$.state").value("WAITING"));
        jdbc.update("UPDATE request_line SET bumped = true WHERE id = ?", roomLine);
        reopen(as(bob), first).andExpect(status().isOk());
        assertThat(waitlistEntries(roomLine)).isZero();
        assertThat(waitlistEntries(gpuLine)).isZero();
        assertThat(jdbc.queryForList("SELECT state FROM request_line WHERE request_id = ?", String.class, first))
                .containsOnly("DRAFT");
        assertThat(jdbc.queryForObject("SELECT bumped FROM request_line WHERE id = ?", Boolean.class, roomLine)).isFalse();
        movableClock.set(clock.instant().plusSeconds(1));
        submit(as(bob), first).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WAITING"));
        detail(as(bob), first)
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(2))
                .andExpect(jsonPath("$.lines[1].waitlistPosition").value(2))
                .andExpect(jsonPath("$.lines[2].line.state").value("DROPPED"));
    }

    private ResultActions reopen(RequestPostProcessor caller, long request) throws Exception {
        return mvc.perform(post("/api/requests/{id}/reopen", request).with(caller));
    }

    private int waitlistEntries(long line) {
        return jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id = ?", Integer.class, line);
    }

    private int transactionsOf(long team) {
        return jdbc.queryForObject("SELECT count(*) FROM transaction WHERE team_id = ?", Integer.class, team);
    }
}
