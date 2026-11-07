package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.Verifies;
import java.time.LocalDate;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Functional test cases of the Draft area (docs/test-cases, Section 4.4), step by step through the API, on the
 * test world of {@link DemoWorldTest} and the days and reference Quotes of the document's Section 2.
 */
class DraftCasesTest extends DemoWorldTest {

    @Test
    @DisplayName("drf-balance-boundary: a Required sum equal to the Available Balance is accepted and leaves exactly 0.00")
    @Verifies("drf-balance-boundary")
    void acceptsARequiredSumEqualToTheAvailableBalance() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(bob));

        // 1. L1 Confocal microscope D 09:00-17:00 (480.00) and L2 Meeting room D 09:00-17:00 (Q6 = 120.00).
        long l1 = line(as(bob), draft, microscope, 1, at(d, 9), at(d, 17), null);
        long l2 = line(as(bob), draft, room, 1, at(d, 9), at(d, 17), null);
        draftView(as(bob), draft)
                .andExpect(jsonPath("$.requiredSum").value(600.00))
                .andExpect(jsonPath("$.availableBalance").value(600.00))
                .andExpect(jsonPath("$.shortfall").value(0.00));

        // 2. Submit: accepted, and the Request is Allocated.
        submit(as(bob), draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ALLOCATED"));

        // 3. The Statement of Research: Charges 480.00 and 120.00; Balance 0.00, not below zero.
        mvc.perform(get("/api/ledger/statements/{team}/{period}", research, period).with(as(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].amount", containsInAnyOrder(480.00, 120.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].line", containsInAnyOrder((int) l1, (int) l2)))
                .andExpect(jsonPath("$.account.balance").value(0.00))
                .andExpect(jsonPath("$.account.available").value(0.00));
        assertThat(available(research)).isEqualByComparingTo("0.00");
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    @Test
    @DisplayName("drf-required-optional: Required by default; a split moves a part to an Optional line; marking it Required again")
    @Verifies("drf-required-optional")
    void splitsLinesToMakeAPartOptional() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));

        // 1. L1 Meeting room x2, D 10:00-12:00 (60.00) and L2 GPU 6 units, D 10:00-12:00 (Q11 = 90.00).
        long l1 = line(as(alice), draft, room, 2, at(d, 10), at(d, 12), null);
        long l2 = line(as(alice), draft, gpu, 6, at(d, 10), at(d, 12), null);
        draftView(as(alice), draft)
                .andExpect(jsonPath("$.lines[0].required").value(true))
                .andExpect(jsonPath("$.lines[1].required").value(true))
                .andExpect(jsonPath("$.requiredSum").value(150.00))
                .andExpect(jsonPath("$.optionalSum").value(0.00));

        // 2. Split L1, moving one room: L1 keeps 1 (30.00, Required); L1b has 1 (30.00, Optional), same type and window.
        String l1bBody = split(as(alice), draft, l1, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.typeId").value(room))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.quote").value(30.00))
                .andExpect(jsonPath("$.required").value(false))
                .andReturn().getResponse().getContentAsString();
        long l1b = ((Number) JsonPath.read(l1bBody, "$.id")).longValue();
        String view = draftView(as(alice), draft).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(view, "$.lines[?(@.id == " + l1b + ")].window"))
                .isEqualTo(JsonPath.read(view, "$.lines[?(@.id == " + l1 + ")].window"));
        draftView(as(alice), draft)
                .andExpect(jsonPath("$.lines[?(@.id == " + l1 + ")].quantity").value(1))
                .andExpect(jsonPath("$.lines[?(@.id == " + l1 + ")].quote").value(30.00))
                .andExpect(jsonPath("$.lines[?(@.id == " + l1 + ")].required").value(true))
                .andExpect(jsonPath("$.requiredSum").value(120.00))
                .andExpect(jsonPath("$.optionalSum").value(30.00));

        // 3. Split L2, moving 2 units: L2 keeps 4 (60.00, Required); L2b has 2 (30.00, Optional).
        split(as(alice), draft, l2, 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.quote").value(30.00))
                .andExpect(jsonPath("$.required").value(false));
        draftView(as(alice), draft)
                .andExpect(jsonPath("$.lines[?(@.id == " + l2 + ")].quantity").value(4))
                .andExpect(jsonPath("$.lines[?(@.id == " + l2 + ")].quote").value(60.00))
                .andExpect(jsonPath("$.requiredSum").value(90.00))
                .andExpect(jsonPath("$.optionalSum").value(60.00));

        // 4. Split L1 again, moving its only room: both parts must keep at least one.
        split(as(alice), draft, l1, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LINE_NOT_ATOMIC"));

        // 5. Mark L1b Required.
        setRequired(as(alice), draft, l1b, true).andExpect(status().isOk()).andExpect(jsonPath("$.required").value(true));
        draftView(as(alice), draft)
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.requiredSum").value(120.00))
                .andExpect(jsonPath("$.optionalSum").value(30.00));
    }

    @Test
    @DisplayName("drf-line-options, steps 1 and 2: an Exclusive line names Room B (Q1 = 30.00); quantity 2 on it is QUANTITY_INVALID")
    @Verifies(value = "drf-line-options", partial = true)
    void letsAnExclusiveLineNameOneResource() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));

        // 1. L1: Meeting room, Specific Resource Room B, quantity 1, D 10:00-12:00.
        addLine(as(alice), draft, room, 1, at(d, 10), at(d, 12), roomB)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.namedResource").value(roomB))
                .andExpect(jsonPath("$.quote").value(30.00));

        // 2. L1 again with quantity 2.
        addLine(as(alice), draft, room, 2, at(d, 10), at(d, 12), roomB)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));

        draftView(as(alice), draft).andExpect(jsonPath("$.lines.length()").value(1));
    }

    /**
     * Steps 3 and 6 need the Priority mark on a Draft line: design Section 7 gives it its own endpoint, PUT
     * {@code /api/requests/{id}/lines/{lineId}/priority}, refused with {@code PRIORITY_NOT_OFFERED} for any line
     * that is not Exclusive. The build has no such endpoint (it answers 404), and step 3's weekly recurrence has
     * no API either, so this runs the Priority part on one occurrence: Q4 = 60.00.
     */
    @Test
    @Disabled("drf-line-options steps 3 and 6: the Priority mark (PUT .../lines/{lineId}/priority) and weekly recurrence are not built")
    @DisplayName("drf-line-options, steps 3 and 6: Priority doubles an Exclusive line's Quote (Q4 = 60.00) and is refused on a GPU line")
    @Verifies(value = "drf-line-options", partial = true)
    void offersPriorityOnExclusiveLinesOnly() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long roomLine = line(as(alice), draft, room, 1, at(d, 10), at(d, 12), null);
        long gpuLine = line(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null);

        setPriority(as(alice), draft, roomLine, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote").value(60.00));
        setPriority(as(alice), draft, gpuLine, true)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRIORITY_NOT_OFFERED"));
    }

    // ---------------------------------------------------------------- API calls

    private ResultActions draftView(RequestPostProcessor caller, long draft) throws Exception {
        return mvc.perform(get("/api/requests/{id}/draft", draft).with(caller));
    }

    private ResultActions split(RequestPostProcessor caller, long draft, long line, int quantity) throws Exception {
        return mvc.perform(post("/api/requests/{id}/lines/{line}/split", draft, line).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": " + quantity + "}"));
    }

    private ResultActions setRequired(RequestPostProcessor caller, long draft, long line, boolean required) throws Exception {
        return mvc.perform(put("/api/requests/{id}/lines/{line}/required", draft, line).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"required\": " + required + "}"));
    }

    private ResultActions setPriority(RequestPostProcessor caller, long draft, long line, boolean priority) throws Exception {
        return mvc.perform(put("/api/requests/{id}/lines/{line}/priority", draft, line).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\": " + priority + "}"));
    }
}
