package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.ledger.LedgerService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Draft editing through the API: the Quotes at current settings, the sums and the shortfall, split,
 * the Required mark, scoping, the role checks, and a refusal test for every line rule.
 *
 * <p>The prices mirror the demo world on types of their own: a room at 10.00 per instance-hour and
 * a GPU pool at 5.00 per unit-hour, both with Monday to Friday 09:00 to 17:00 at 1.5 and weekends
 * at 0.5. Windows are computed from the Clock: next Tuesday, never a fixed date.
 */
class DraftRequestTest extends PostgresTest {

    @Autowired
    private Clock clock;

    @Autowired
    private LedgerService ledger;

    private long period;
    private long platform;
    private String alice;
    private String ben;
    private String bob;
    private String erin;
    private long dave;
    private long room;
    private long roomA;
    private long gpu;

    @BeforeEach
    void createTheTeamsTypesAndPrices() {
        period = testData().periodContaining(LocalDate.now(clock));
        platform = testData().team(unique("Platform"));
        alice = unique("alice");
        testData().member(platform, alice, Role.MANAGER);
        ben = unique("ben");
        testData().member(platform, ben);
        long research = testData().team(unique("Research"));
        bob = unique("bob");
        testData().member(research, bob, Role.MANAGER);
        long operations = testData().team(unique("Operations"));
        erin = unique("erin");
        testData().member(operations, erin, Role.PLANNER);
        dave = testData().member(operations, unique("dave"), Role.RESOURCE_OWNER);

        room = testData().resourceType(unique("Meeting room"), "EXCLUSIVE");
        priced(room, "10.00");
        roomA = testData().resource(room, "Room A", dave, 1);
        gpu = testData().resourceType(unique("GPU"), "METERED");
        priced(gpu, "5.00");
        testData().resource(gpu, unique("gpu-node"), dave, 16);
    }

    @Test
    @DisplayName("Draft.Quote.Line and Draft.Quote.Totals: Room A 30.00, 4 GPU units 1120.00, Required 1150.00 of 5000.00")
    void showsTheQuotesTheSumsAndTheAvailableBalance() throws Exception {
        grant(platform, "5000.00");
        long draft = createDraft(alice);

        addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(16), roomA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocationClass").value("EXCLUSIVE"))
                .andExpect(jsonPath("$.namedResource").value(roomA))
                .andExpect(jsonPath("$.namedResourceName").value("Room A"))
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.quote").value(30.00));
        addLine(alice, draft, gpu, 4, tuesdayAt(9), tuesdayAt(9).plusSeconds(48 * 3600), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocationClass").value("METERED"))
                .andExpect(jsonPath("$.quantity").value(4))
                .andExpect(jsonPath("$.quote").value(1120.00));

        mvc.perform(get("/api/requests/{id}/draft", draft).with(signedInAs(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.id").value(draft))
                .andExpect(jsonPath("$.request.state").value("DRAFT"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].quote").value(30.00))
                .andExpect(jsonPath("$.lines[1].quote").value(1120.00))
                .andExpect(jsonPath("$.requiredSum").value(1150.00))
                .andExpect(jsonPath("$.optionalSum").value(0.00))
                .andExpect(jsonPath("$.availableBalance").value(5000.00))
                .andExpect(jsonPath("$.shortfall").value(0.00));
    }

    @Test
    @DisplayName("Draft.Create: a Manager's new Draft belongs to their Team and is recorded in its history")
    void createsADraftForTheCallersTeam() throws Exception {
        String body = mvc.perform(post("/api/requests").with(signedInAs(alice)).with(xsrf()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.team.id").value(platform))
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long draft = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(jdbc.queryForList("SELECT coalesce(from_state, '-') || '>' || to_state FROM request_event WHERE request_id = ?",
                String.class, draft)).containsExactly("->DRAFT");
    }

    @Test
    @DisplayName("Draft.Quote.Totals: the shortfall is the Required sum above the Available Balance; Optional lines do not count")
    void showsTheShortfallOfTheRequiredLines() throws Exception {
        grant(platform, "1000.00");
        long draft = createDraft(alice);
        long gpuLine = lineId(addLine(alice, draft, gpu, 4, tuesdayAt(9), tuesdayAt(9).plusSeconds(48 * 3600), null));

        draftView(alice, draft)
                .andExpect(jsonPath("$.requiredSum").value(1120.00))
                .andExpect(jsonPath("$.availableBalance").value(1000.00))
                .andExpect(jsonPath("$.shortfall").value(120.00));

        mvc.perform(put("/api/requests/{id}/lines/{line}/required", draft, gpuLine).with(signedInAs(alice)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"required\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(false));
        draftView(alice, draft)
                .andExpect(jsonPath("$.requiredSum").value(0.00))
                .andExpect(jsonPath("$.optionalSum").value(1120.00))
                .andExpect(jsonPath("$.shortfall").value(0.00));
    }

    @Test
    @DisplayName("A type without pricing settings shows no Quote and counts 0.00")
    void showsNoQuoteForATypeWithoutPrices() throws Exception {
        long printer = testData().resourceType(unique("3D printer"), "EXCLUSIVE");
        long draft = createDraft(alice);
        addLine(alice, draft, printer, 1, tuesdayAt(14), tuesdayAt(16), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quote").doesNotExist());
        addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(16), null);

        draftView(alice, draft)
                .andExpect(jsonPath("$.lines[0].quote").isEmpty())
                .andExpect(jsonPath("$.requiredSum").value(30.00));
    }

    @Test
    @DisplayName("Draft.Line.Split: part of a line moves to a new Optional line of the same type and window")
    void splitsALine() throws Exception {
        long draft = createDraft(alice);
        long rooms = lineId(addLine(alice, draft, room, 3, tuesdayAt(14), tuesdayAt(16), null));

        split(alice, draft, rooms, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.typeId").value(room))
                .andExpect(jsonPath("$.quote").value(30.00));

        draftView(alice, draft)
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].required").value(true))
                .andExpect(jsonPath("$.requiredSum").value(60.00))
                .andExpect(jsonPath("$.optionalSum").value(30.00));
    }

    @Test
    @DisplayName("Draft.Line.Split: a Metered line splits its units")
    void splitsTheUnitsOfAMeteredLine() throws Exception {
        long draft = createDraft(alice);
        long units = lineId(addLine(alice, draft, gpu, 4, tuesdayAt(9), tuesdayAt(10), null));

        split(alice, draft, units, 3).andExpect(status().isCreated()).andExpect(jsonPath("$.quantity").value(3));
        draftView(alice, draft)
                .andExpect(jsonPath("$.lines[0].quantity").value(1))
                .andExpect(jsonPath("$.lines[1].quantity").value(3));
    }

    @Test
    @DisplayName("Split refuses with 422 LINE_NOT_ATOMIC unless both parts keep at least 1")
    void refusesASplitThatLeavesNothing() throws Exception {
        long draft = createDraft(alice);
        long rooms = lineId(addLine(alice, draft, room, 2, tuesdayAt(14), tuesdayAt(16), null));
        long single = lineId(addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(16), null));

        for (int part : new int[] {0, 2, 3}) {
            refused(split(alice, draft, rooms, part), 422, "LINE_NOT_ATOMIC");
        }
        refused(split(alice, draft, single, 1), 422, "LINE_NOT_ATOMIC");
    }

    @Test
    void editsAndRemovesALine() throws Exception {
        long draft = createDraft(alice);
        long line = lineId(addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(16), null));

        mvc.perform(put("/api/requests/{id}/lines/{line}", draft, line).with(signedInAs(alice)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineEdit(1, tuesdayAt(14), tuesdayAt(17), roomA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namedResource").value(roomA))
                .andExpect(jsonPath("$.quote").value(45.00));
        refused(mvc.perform(put("/api/requests/{id}/lines/{line}", draft, line).with(signedInAs(alice)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(lineEdit(1, tuesdayAt(17), tuesdayAt(14), null))), 422, "WINDOW_INVALID");

        mvc.perform(delete("/api/requests/{id}/lines/{line}", draft, line).with(signedInAs(alice)).with(xsrf()))
                .andExpect(status().isNoContent());
        draftView(alice, draft).andExpect(jsonPath("$.lines.length()").value(0));
    }

    // ---------------------------------------------------------------- Line rules

    @Test
    @DisplayName("A window that ends before it starts, or off the whole hour, is refused with 422 WINDOW_INVALID")
    void refusesAMalformedWindow() throws Exception {
        long draft = createDraft(alice);
        refused(addLine(alice, draft, room, 1, tuesdayAt(16), tuesdayAt(14), null), 422, "WINDOW_INVALID");
        refused(addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(14), null), 422, "WINDOW_INVALID");
        refused(addLine(alice, draft, room, 1, tuesdayAt(14).plusSeconds(30 * 60), tuesdayAt(16), null),
                422, "WINDOW_INVALID");
    }

    @Test
    @DisplayName("Draft.Line.Validate: a window that does not start in the future is refused with 422 WINDOW_NOT_FUTURE")
    void refusesAWindowInThePast() throws Exception {
        long draft = createDraft(alice);
        Instant lastHour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        refused(addLine(alice, draft, room, 1, lastHour, lastHour.plusSeconds(3600), null), 422, "WINDOW_NOT_FUTURE");
    }

    @Test
    @DisplayName("Draft.Line.Validate: a retired Resource Type is refused with 422 TYPE_RETIRED")
    void refusesARetiredType() throws Exception {
        long retired = testData().resourceType(unique("Old room"), "EXCLUSIVE");
        jdbc.update("UPDATE resource_type SET retired_at = now() WHERE id = ?", retired);
        refused(addLine(alice, createDraft(alice), retired, 1, tuesdayAt(14), tuesdayAt(16), null), 422, "TYPE_RETIRED");
    }

    @Test
    @DisplayName("Draft.Line.Validate: a quantity below 1 is refused with 422 QUANTITY_INVALID")
    void refusesAQuantityBelowOne() throws Exception {
        long draft = createDraft(alice);
        refused(addLine(alice, draft, room, 0, tuesdayAt(14), tuesdayAt(16), null), 422, "QUANTITY_INVALID");
        refused(addLine(alice, draft, gpu, -1, tuesdayAt(14), tuesdayAt(16), null), 422, "QUANTITY_INVALID");
    }

    @Test
    @DisplayName("A line naming one Resource asks for 1 instance, else 422 QUANTITY_INVALID")
    void refusesSeveralInstancesOfANamedResource() throws Exception {
        refused(addLine(alice, createDraft(alice), room, 2, tuesdayAt(14), tuesdayAt(16), roomA), 422, "QUANTITY_INVALID");
    }

    @Test
    @DisplayName("A named Resource of another type is refused with 422 TYPE_MISMATCH")
    void refusesANamedResourceOfAnotherType() throws Exception {
        long otherRoom = testData().resourceType(unique("Lab"), "EXCLUSIVE");
        refused(addLine(alice, createDraft(alice), otherRoom, 1, tuesdayAt(14), tuesdayAt(16), roomA), 422, "TYPE_MISMATCH");
    }

    @Test
    @DisplayName("A Metered line cannot name a Resource: 422 NAMED_RESOURCE_NOT_OFFERED")
    void refusesANamedResourceOnAMeteredLine() throws Exception {
        long node = testData().resource(gpu, unique("gpu-node"), dave, 8);
        refused(addLine(alice, createDraft(alice), gpu, 1, tuesdayAt(14), tuesdayAt(16), node),
                422, "NAMED_RESOURCE_NOT_OFFERED");
    }

    @Test
    @DisplayName("A retired named Resource is refused with 422 RESOURCE_RETIRED")
    void refusesARetiredNamedResource() throws Exception {
        long old = testData().resource(room, unique("Room Z"), dave, 1);
        jdbc.update("UPDATE resource SET retired_at = now() WHERE id = ?", old);
        refused(addLine(alice, createDraft(alice), room, 1, tuesdayAt(14), tuesdayAt(16), old), 422, "RESOURCE_RETIRED");
    }

    @Test
    @DisplayName("An unknown type or Resource is refused with 422 TYPE_UNKNOWN or RESOURCE_UNKNOWN")
    void refusesUnknownTypesAndResources() throws Exception {
        long draft = createDraft(alice);
        refused(addLine(alice, draft, Long.MAX_VALUE, 1, tuesdayAt(14), tuesdayAt(16), null), 422, "TYPE_UNKNOWN");
        refused(addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(16), Long.MAX_VALUE), 422, "RESOURCE_UNKNOWN");
    }

    @Test
    @DisplayName("Matched lines are not in Demo 1: a Matched type is refused with 422 CLASS_NOT_OFFERED")
    void refusesAMatchedType() throws Exception {
        long person = jdbc.queryForObject("SELECT id FROM resource_type WHERE name = 'Person'", Long.class);
        refused(addLine(alice, createDraft(alice), person, 1, tuesdayAt(14), tuesdayAt(16), null), 422, "CLASS_NOT_OFFERED");
    }

    @Test
    @DisplayName("Lines of a Request that is not a Draft cannot change: 409 WRONG_STATE")
    void refusesEditsOutsideDraft() throws Exception {
        long draft = createDraft(alice);
        long line = lineId(addLine(alice, draft, room, 2, tuesdayAt(14), tuesdayAt(16), null));
        jdbc.update("UPDATE request SET state = 'WITHDRAWN' WHERE id = ?", draft);

        refused(addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(16), null), 409, "WRONG_STATE");
        refused(split(alice, draft, line, 1), 409, "WRONG_STATE");
        refused(mvc.perform(put("/api/requests/{id}/lines/{line}/required", draft, line).with(signedInAs(alice))
                .with(xsrf()).contentType(MediaType.APPLICATION_JSON).content("{\"required\": false}")), 409, "WRONG_STATE");
        refused(mvc.perform(delete("/api/requests/{id}/lines/{line}", draft, line).with(signedInAs(alice)).with(xsrf())),
                409, "WRONG_STATE");
    }

    // ---------------------------------------------------------------- Scope and roles

    @Test
    @DisplayName("Another Team's Draft is 404 NOT_FOUND for a Manager, to read and to edit")
    void hidesAnotherTeamsDraft() throws Exception {
        long draft = createDraft(alice);
        long line = lineId(addLine(alice, draft, room, 2, tuesdayAt(14), tuesdayAt(16), null));

        refused(draftView(bob, draft), 404, "NOT_FOUND");
        refused(addLine(bob, draft, room, 1, tuesdayAt(14), tuesdayAt(16), null), 404, "NOT_FOUND");
        refused(split(bob, draft, line, 1), 404, "NOT_FOUND");
        refused(mvc.perform(get("/api/requests").param("teamId", Long.toString(platform)).with(signedInAs(bob))),
                404, "NOT_FOUND");
    }

    @Test
    @DisplayName("A line of another Request is 404 NOT_FOUND under this Request's path")
    void refusesALineOfAnotherRequest() throws Exception {
        long first = createDraft(alice);
        long second = createDraft(alice);
        long line = lineId(addLine(alice, first, room, 2, tuesdayAt(14), tuesdayAt(16), null));
        refused(split(alice, second, line, 1), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("A Member without MANAGER gets 403 FORBIDDEN for every Draft endpoint")
    void refusesAMemberWithoutManager() throws Exception {
        long draft = createDraft(alice);
        refused(mvc.perform(post("/api/requests").with(signedInAs(ben)).with(xsrf())), 403, "FORBIDDEN");
        refused(mvc.perform(get("/api/requests").with(signedInAs(ben))), 403, "FORBIDDEN");
        refused(draftView(ben, draft), 403, "FORBIDDEN");
        refused(addLine(ben, draft, room, 1, tuesdayAt(14), tuesdayAt(16), null), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("A Capacity Planner lists and reads every Team's Requests but cannot edit them (403)")
    void letsThePlannerReadButNotEdit() throws Exception {
        long draft = createDraft(alice);
        addLine(alice, draft, room, 1, tuesdayAt(14), tuesdayAt(16), null);

        mvc.perform(get("/api/requests").param("teamId", Long.toString(platform)).with(signedInAs(erin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + draft + ")].requiredSum").value(30.00));
        draftView(erin, draft).andExpect(status().isOk()).andExpect(jsonPath("$.requiredSum").value(30.00));
        refused(mvc.perform(post("/api/requests").with(signedInAs(erin)).with(xsrf())), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("GET /api/requests: the Manager's own Team's Requests, newest first, with line count and Required sum")
    void listsTheTeamsRequests() throws Exception {
        long older = createDraft(alice);
        addLine(alice, older, room, 1, tuesdayAt(14), tuesdayAt(16), null);
        addLine(alice, older, gpu, 4, tuesdayAt(9), tuesdayAt(9).plusSeconds(48 * 3600), null);
        long newer = createDraft(alice);
        createDraft(bob);

        mvc.perform(get("/api/requests").with(signedInAs(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newer))
                .andExpect(jsonPath("$.content[0].lineCount").value(0))
                .andExpect(jsonPath("$.content[0].requiredSum").value(0.00))
                .andExpect(jsonPath("$.content[1].id").value(older))
                .andExpect(jsonPath("$.content[1].state").value("DRAFT"))
                .andExpect(jsonPath("$.content[1].lineCount").value(2))
                .andExpect(jsonPath("$.content[1].requiredSum").value(1150.00));
        mvc.perform(get("/api/requests").param("state", "WAITING").with(signedInAs(alice)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------------------------------------------------------------- Helpers

    /** The demo world's prices for a type: the Unit Price, peak weekdays at 1.5, weekends at 0.5. */
    private void priced(long type, String unitPrice) {
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, ?::numeric)", type, unitPrice);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '12345', '09:00', '17:00', 1.5)", type);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '67', '00:00', '00:00', 0.5)", type);
    }

    private void grant(long team, String amount) {
        ledger.postGrant(new TeamId(team), new PeriodId(period), Credits.of(amount), null, null);
    }

    /** Next Tuesday (never today) at this hour, company time. */
    private Instant tuesdayAt(int hour) {
        return LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.TUESDAY))
                .atTime(LocalTime.of(hour, 0)).atZone(clock.getZone()).toInstant();
    }

    private long createDraft(String manager) throws Exception {
        String body = mvc.perform(post("/api/requests").with(signedInAs(manager)).with(xsrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions addLine(String manager, long draft, long type, int quantity, Instant start, Instant end,
            Long namedResource) throws Exception {
        String named = namedResource == null ? "" : ", \"namedResourceId\": " + namedResource;
        return mvc.perform(post("/api/requests/{id}/lines", draft).with(signedInAs(manager)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeId\": " + type + ", \"quantity\": " + quantity + ", \"start\": \"" + start
                        + "\", \"end\": \"" + end + "\"" + named + "}"));
    }

    private static String lineEdit(int quantity, Instant start, Instant end, Long namedResource) {
        String named = namedResource == null ? "" : ", \"namedResourceId\": " + namedResource;
        return "{\"quantity\": " + quantity + ", \"start\": \"" + start + "\", \"end\": \"" + end + "\"" + named + "}";
    }

    private ResultActions split(String manager, long draft, long line, int quantity) throws Exception {
        return mvc.perform(post("/api/requests/{id}/lines/{line}/split", draft, line).with(signedInAs(manager)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": " + quantity + "}"));
    }

    private ResultActions draftView(String member, long draft) throws Exception {
        return mvc.perform(get("/api/requests/{id}/draft", draft).with(signedInAs(member)));
    }

    private static long lineId(ResultActions added) throws Exception {
        String body = added.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static void refused(ResultActions call, int status, String code) throws Exception {
        call.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
}
