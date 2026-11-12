package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The rules of Person lines, the Shortlist, selection, and approval (issue #5), each refusal with its
 * code, and the transitions a Person line drives: an Optional one never holds a Request in Pending, a
 * named Person is selected at submission, a selection brings a Waiting Request back to Pending, and a
 * withdrawal offers what it held to the waitlist.
 */
class MatchedRulesTest extends MatchedWorldTest {

    // ---------------------------------------------------------------- Person lines in a Draft

    @Test
    @DisplayName("A Person line covers whole future days and names a Person or asks for skills, not both")
    void refusesInvalidPersonLines() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));

        refused(addLine(as(alice), draft, person, 1, at(d, 9), at(d, 17), null), 422, "WINDOW_INVALID");
        LocalDate today = LocalDate.now(clock);
        refused(addPersonLine(as(alice), draft, 1, today, today.plusDays(1), statistics), 422, "WINDOW_NOT_FUTURE");
        refused(postRaw(draft, "\"namedMemberId\": " + world.dave().id() + ", \"skills\": [" + statistics + "]", d),
                400, "VALIDATION_FAILED");
        refused(postRaw(draft, "\"namedResourceId\": " + roomA, d), 422, "NAMED_RESOURCE_NOT_OFFERED");
        refused(addPersonLine(as(alice), draft, 1, d, d.plusDays(1), Long.MAX_VALUE), 422, "SKILL_UNKNOWN");
        refused(addNamedPersonLine(as(alice), draft, 1, d, d.plusDays(1), Long.MAX_VALUE), 422, "MEMBER_UNKNOWN");
        refused(addNamedPersonLine(as(alice), draft, 2, d, d.plusDays(1), world.dave().id()), 422, "QUANTITY_INVALID");
        refused(addNamedPersonLine(as(alice), draft, 1, d, d.plusDays(1), world.ben().id()), 422, "OWN_TEAM");
    }

    @Test
    @DisplayName("A Person line is edited whole, and a split keeps its skills on the new Optional line")
    void editsAndSplitsAPersonLine() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long line = personLine(as(alice), draft, 3, d, d.plusDays(1), statistics);

        mvc.perform(put("/api/requests/{id}/lines/{line}", draft, line).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 1, \"start\": \"" + midnight(d) + "\", \"end\": \""
                                + midnight(d.plusDays(1)) + "\", \"namedMemberId\": " + world.rita().id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namedMemberName").value("Rita Gomez"))
                .andExpect(jsonPath("$.skills", empty()))
                .andExpect(jsonPath("$.days.to").value(d.toString()));
        refused(mvc.perform(put("/api/requests/{id}/lines/{line}", draft, line).with(as(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": 1, \"start\": \"" + midnight(d) + "\", \"end\": \"" + midnight(d.plusDays(1))
                        + "\", \"namedMemberId\": " + world.ben().id() + "}")), 422, "OWN_TEAM");

        long skilled = personLine(as(alice), draft, 3, d, d.plusDays(1), statistics, microscopy);
        mvc.perform(post("/api/requests/{id}/lines/{line}/split", draft, skilled).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.skills.length()").value(2));
    }

    // ---------------------------------------------------------------- the Shortlist

    @Test
    @DisplayName("The Shortlist leaves out inactive Members, Members not available every day, and Members lent then")
    void theShortlistLeavesOutTheIneligible() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("PENDING"));
        shortlist(as(alice), a1, m1).andExpect(jsonPath("$.candidates[*].name", contains("Dave Kim", "Rita Gomez")));

        // Rita is deactivated: she leaves the Shortlist at once.
        testData().deactivate(world.rita().id());
        shortlist(as(alice), a1, m1).andExpect(jsonPath("$.candidates[*].name", contains("Dave Kim")));

        // Dave is lent to Research on D+1, a day of the window: he leaves it too.
        testData().loan(world.dave().id(), operations, research, d.plusDays(1), d.plusDays(1));
        shortlist(as(alice), a1, m1).andExpect(jsonPath("$.candidates", empty()));

        // Carol gains the skill but has no availability: she is not offered.
        testData().holds(carolId, statistics);
        shortlist(as(alice), a1, m1).andExpect(jsonPath("$.candidates", empty()));
        testData().available(carolId, LocalDate.now(clock), d.plusDays(60));
        shortlist(as(alice), a1, m1).andExpect(jsonPath("$.candidates[*].name", contains("Carol Davis")));
    }

    @Test
    @DisplayName("A Shortlist is for a submitted Person line in the caller's scope: WRONG_STATE, else 404")
    void theShortlistIsForASubmittedPersonLine() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long m1 = personLine(as(alice), draft, 1, d, d.plusDays(1), statistics);
        long room1 = line(as(alice), draft, room, 1, at(d, 10), at(d, 12), null);
        refused(shortlist(as(alice), draft, m1), 409, "WRONG_STATE");
        submit(as(alice), draft).andExpect(status().isOk());
        refused(shortlist(as(alice), draft, room1), 409, "WRONG_STATE");
        shortlist(as(bob), draft, m1).andExpect(status().isNotFound());
        shortlist(as(alice), draft, m1).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- selection and decisions

    @Test
    @DisplayName("Selecting: OWN_TEAM for the requesting Team's Member, NOT_A_CANDIDATE for one refused before")
    void refusesInvalidSelections() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 2, d, d.plusDays(1), statistics);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("PENDING"));

        refused(select(as(alice), a1, m1, world.ben().id(), world.rita().id()), 422, "OWN_TEAM");
        refused(select(as(alice), a1, m1, world.dave().id()), 422, "LINE_NOT_ATOMIC");
        refused(select(as(alice), a1, m1, world.dave().id(), world.dave().id()), 422, "LINE_NOT_ATOMIC");
        select(as(alice), a1, m1, world.dave().id(), world.rita().id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].candidates[*].name", contains("Dave Kim", "Rita Gomez")));
        refused(select(as(alice), a1, m1, carolId), 422, "LINE_NOT_ATOMIC");

        // Oscar refuses Dave: one more is to be selected, but not Dave again.
        decide(as(oscar), jdbc.queryForObject("""
                SELECT a.id FROM approval a JOIN candidate c ON c.id = a.candidate_id
                WHERE c.line_id = ? AND c.member_id = ?""", Long.class, m1, world.dave().id()), "REFUSE", "Busy")
                .andExpect(status().isOk());
        refused(select(as(alice), a1, m1, world.dave().id()), 422, "NOT_A_CANDIDATE");
        select(as(bob), a1, m1, world.dave().id()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("NOT_A_CANDIDATE: an approval checks eligibility again, since a Loan may have formed since selection")
    void anApprovalChecksEligibilityAgain() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a1).andExpect(status().isOk());
        select(as(alice), a1, m1, world.dave().id()).andExpect(status().isOk());
        testData().loan(world.dave().id(), operations, research, d, d);

        refused(decide(as(oscar), approvalOf(m1), "APPROVE", "Fine by me"), 422, "NOT_A_CANDIDATE");
        assertThat(jdbc.queryForObject("SELECT state FROM approval WHERE id = ?", String.class, approvalOf(m1)))
                .isEqualTo("OUTSTANDING");
        // Refusing needs no eligibility.
        decide(as(oscar), approvalOf(m1), "REFUSE", "He is lent elsewhere").andExpect(status().isOk());
    }

    @Test
    @DisplayName("A refusal is recorded with its reason; the line awaits a replacement, and Alice is told")
    void aRefusalAsksForAReplacement() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a1).andExpect(status().isOk());
        select(as(alice), a1, m1, world.dave().id()).andExpect(status().isOk());

        decide(as(oscar), approvalOf(m1), "REFUSE", "Dave is on another project")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REFUSED"))
                .andExpect(jsonPath("$.reason").value("Dave is on another project"));
        detail(as(alice), a1)
                .andExpect(jsonPath("$.request.state").value("PENDING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lines[0].candidates[0].state").value("REFUSED"));
        assertThat(jdbc.queryForList("SELECT text FROM notification WHERE member_id = ? AND kind = 'REQUEST_UPDATE'",
                String.class, world.alice().id())).anySatisfy(text -> assertThat(text).contains("refused Dave Kim"));

        // The replacement goes to Rita's Manager, Bob.
        select(as(alice), a1, m1, world.rita().id()).andExpect(status().isOk());
        approvals(as(bob)).andExpect(jsonPath("$[*].candidateName", contains("Rita Gomez")));
    }

    @Test
    @DisplayName("Only the approver decides: another Team's Manager is FORBIDDEN, an unknown approval 404")
    void onlyTheApproverDecides() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long m1 = personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a1).andExpect(status().isOk());
        select(as(alice), a1, m1, world.dave().id()).andExpect(status().isOk());

        refused(decide(as(bob), approvalOf(m1), "APPROVE", ""), 403, "FORBIDDEN");
        decide(as(oscar), Long.MAX_VALUE, "APPROVE", "").andExpect(status().isNotFound());
        mvc.perform(post("/api/approvals/{id}/decision", approvalOf(m1)).with(as(dave))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"APPROVE\"}"))
                .andExpect(status().isForbidden());
        approvals(as(bob)).andExpect(jsonPath("$", empty()));
    }

    // ---------------------------------------------------------------- transitions

    @Test
    @DisplayName("A named Person counts as the Manager's selection: the approval goes to their Manager at submission")
    void aNamedPersonIsSelectedAtSubmission() throws Exception {
        LocalDate d = dayD();
        long b1 = createDraft(as(bob));
        id(addNamedPersonLine(as(bob), b1, 1, d, d.plusDays(1), world.dave().id()).andExpect(status().isCreated()));
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("PENDING"));

        approvals(as(oscar))
                .andExpect(jsonPath("$[*].candidateName", contains("Dave Kim")))
                .andExpect(jsonPath("$[0].requestingTeam.id").value((int) research));
        assertThat(notificationsOf(world.dave())).containsExactly("CANDIDATE_SELECTED");
    }

    @Test
    @DisplayName("An Optional Person line never holds a Request in Pending: it is Allocated, the line open, the window set")
    void anOptionalPersonLineDoesNotHoldTheRequest() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long room1 = line(as(alice), a1, room, 1, at(d, 10), at(d, 12), roomA);
        long named = id(addNamedPersonLine(as(alice), a1, 1, d, d.plusDays(1), world.rita().id())
                .andExpect(status().isCreated()));
        markOptional(as(alice), a1, named);

        String submitted = submit(as(alice), a1)
                .andExpect(jsonPath("$.state").value("ALLOCATED"))
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(submitted, "$.windowEndsAt")).isNotNull();
        detail(as(alice), a1)
                .andExpect(jsonPath("$.lines[?(@.line.id == " + room1 + ")].line.state").value(hasItem("ALLOCATED")))
                .andExpect(jsonPath("$.lines[?(@.line.id == " + named + ")].line.state").value(hasItem("AWAITING_APPROVAL")));
        approvals(as(bob)).andExpect(jsonPath("$[*].candidateName", contains("Rita Gomez")));
        assertThat(available(platform)).isEqualByComparingTo("4970.00");
    }

    @Test
    @DisplayName("An Optional Person line with too few Members is Dropped; a Required one leaves the Request Waiting")
    void aShortShortlistDropsAnOptionalLine() throws Exception {
        LocalDate d = dayD();
        long b1 = createDraft(as(bob));
        line(as(bob), b1, room, 1, at(d, 10), at(d, 12), null);
        long m = personLine(as(bob), b1, 3, d, d.plusDays(1), statistics);
        markOptional(as(bob), b1, m);

        submit(as(bob), b1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(bob), b1)
                .andExpect(jsonPath("$.lines[?(@.line.id == " + m + ")].line.state").value(hasItem("DROPPED")))
                .andExpect(jsonPath("$.lines[?(@.line.id == " + m + ")].reason").value(hasItem("SHORTLIST_TOO_SHORT")));
        assertThat(jdbc.queryForObject("SELECT window_ends_at FROM request WHERE id = ?", Object.class, b1)).isNull();
    }

    @Test
    @DisplayName("Selecting for a Waiting Request whose window runs brings it back to Pending with its Holds, window unchanged")
    void aSelectionBringsAWaitingRequestBackToPending() throws Exception {
        LocalDate d = dayD();
        long b1 = createDraft(as(bob));
        long roomLine = line(as(bob), b1, room, 1, at(d, 10), at(d, 12), null);
        id(addNamedPersonLine(as(bob), b1, 1, d, d.plusDays(1), world.dave().id()).andExpect(status().isCreated()));
        long short2 = personLine(as(bob), b1, 2, d, d.plusDays(1), statistics);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
        String windowEnd = jdbc.queryForObject("SELECT window_ends_at::text FROM request WHERE id = ?", String.class, b1);
        assertThat(windowEnd).isNotNull();
        detail(as(bob), b1)
                .andExpect(jsonPath("$.lines[?(@.line.id == " + roomLine + ")].line.state").value(hasItem("EVALUATED")))
                .andExpect(jsonPath("$.lines[?(@.line.id == " + short2 + ")].reason").value(hasItem("SHORTLIST_TOO_SHORT")));

        // Carol becomes a Candidate; Bob selects Dave and Carol for the short line.
        testData().holds(carolId, statistics);
        testData().available(carolId, LocalDate.now(clock), d.plusDays(60));
        select(as(bob), b1, short2, world.dave().id(), carolId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("PENDING"))
                .andExpect(jsonPath("$.lines[?(@.line.id == " + roomLine + ")].line.state").value(hasItem("HELD")));
        assertThat(jdbc.queryForObject("SELECT window_ends_at::text FROM request WHERE id = ?", String.class, b1))
                .isEqualTo(windowEnd);
        assertThat(account(research)).containsEntry("held", new java.math.BigDecimal("30.00"));
    }

    @Test
    @DisplayName("A Request with a Required Person line is never allocated from the waitlist before Loans exist (#6)")
    void aWaitingPersonRequestIsNotAllocatedFromTheWaitlist() throws Exception {
        LocalDate d = dayD();
        // Alice holds Room A for a Pending Request.
        long a1 = createDraft(as(alice));
        personLine(as(alice), a1, 1, d, d.plusDays(1), statistics);
        line(as(alice), a1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("PENDING"));

        // Bob waits for Room A without Person lines; withdrawing A1 offers Room A to him, and he is Allocated.
        long b1 = createDraft(as(bob));
        line(as(bob), b1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
        withdraw(as(alice), a1).andExpect(jsonPath("$.state").value("WITHDRAWN"));
        detail(as(bob), b1).andExpect(jsonPath("$.request.state").value("ALLOCATED"));

        // Oscar waits for Room A behind Bob with a Person line whose Shortlist is short; when Bob releases it,
        // Oscar's Request stays Waiting.
        long o1 = createDraft(as(oscar));
        line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomA);
        personLine(as(oscar), o1, 5, d, d.plusDays(1), statistics);
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("WAITING"));
        long bobsLine = jdbc.queryForObject("SELECT id FROM request_line WHERE request_id = ?", Long.class, b1);
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", b1, bobsLine).with(as(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());
        detail(as(oscar), o1)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[*].line.state", not(hasItem("ALLOCATED"))));
    }

    @Test
    @DisplayName("Approvals is for Managers and Planners; a Member who manages no one sees 403")
    void approvalsAreForManagers() throws Exception {
        approvals(as(dave)).andExpect(status().isForbidden());
        approvals(as(oscar)).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions postRaw(long draft, String fields, LocalDate d) throws Exception {
        return mvc.perform(post("/api/requests/{id}/lines", draft).with(as(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeId\": " + person + ", \"quantity\": 1, \"start\": \"" + midnight(d) + "\", \"end\": \""
                        + midnight(d.plusDays(2)) + "\", " + fields + "}"));
    }

    private static void refused(ResultActions call, int status, String code) throws Exception {
        call.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
}
