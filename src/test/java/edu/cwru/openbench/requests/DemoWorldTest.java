package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.ledger.LedgerService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base of the submission tests: the demo world's shape (.scratch/demo1/demo-world.md) on Teams,
 * Members, and types of its own, since the shared test database holds other tests' rows and not the
 * demo seed. Platform has 5000.00 and Research 600.00 in the Period of today; a Meeting room type at
 * 10.00 per instance-hour with Room A and Room B, a Confocal microscope at 40.00 with Microscope 1,
 * and a GPU pool of 16 units at 5.00 per unit-hour, all with Monday to Friday 09:00 to 17:00 at 1.5
 * and weekends at 0.5; Research may hold 8 GPU units. Windows lie in next week, from the Clock.
 *
 * <p>The helpers call the API as a caller given as a {@link RequestPostProcessor}: {@link #as} for a
 * Member without the login round trip, or a real session with its CSRF token.
 */
abstract class DemoWorldTest extends PostgresTest {

    @Autowired
    protected Clock clock;

    @Autowired
    protected LedgerService ledger;

    protected long period;
    protected long platform;
    protected long research;
    protected String alice;
    protected String ben;
    protected String bob;
    protected String erin;
    protected long room;
    protected long roomA;
    protected String roomAName;
    protected long roomB;
    protected long microscope;
    protected long gpu;

    @BeforeEach
    void createTheDemoWorld() {
        period = testData().periodContaining(LocalDate.now(clock));
        platform = testData().team(unique("Platform"));
        alice = unique("alice");
        testData().member(platform, alice, Role.MANAGER);
        ben = unique("ben");
        testData().member(platform, ben);
        research = testData().team(unique("Research"));
        bob = unique("bob");
        testData().member(research, bob, Role.MANAGER);
        long operations = testData().team(unique("Operations"));
        erin = unique("erin");
        testData().member(operations, erin, Role.PLANNER);
        long dave = testData().member(operations, unique("dave"), Role.RESOURCE_OWNER);

        room = testData().resourceType(unique("Meeting room"), "EXCLUSIVE");
        priced(room, "10.00");
        roomAName = unique("Room A");
        roomA = testData().resource(room, roomAName, dave, 1);
        roomB = testData().resource(room, unique("Room B"), dave, 1);
        microscope = testData().resourceType(unique("Confocal microscope"), "EXCLUSIVE");
        priced(microscope, "40.00");
        testData().resource(microscope, unique("Microscope 1"), dave, 1);
        gpu = testData().resourceType(unique("GPU"), "METERED");
        priced(gpu, "5.00");
        testData().resource(gpu, unique("gpu-node-a"), dave, 8);
        testData().resource(gpu, unique("gpu-node-b"), dave, 8);
        testData().quota(research, gpu, 8);

        grant(platform, "5000.00");
        grant(research, "600.00");
    }

    // ---------------------------------------------------------------- callers

    /** The Member, signed in without the login round trip, sending a CSRF token on every call. */
    protected RequestPostProcessor as(String username) {
        RequestPostProcessor member = signedInAs(username);
        RequestPostProcessor token = xsrf();
        return request -> token.postProcessRequest(member.postProcessRequest(request));
    }

    // ---------------------------------------------------------------- API calls

    protected long createDraft(RequestPostProcessor caller) throws Exception {
        String body = mvc.perform(post("/api/requests").with(caller))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    protected ResultActions addLine(RequestPostProcessor caller, long draft, long type, int quantity, Instant start,
            Instant end, Long namedResource) throws Exception {
        String named = namedResource == null ? "" : ", \"namedResourceId\": " + namedResource;
        return mvc.perform(post("/api/requests/{id}/lines", draft).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeId\": " + type + ", \"quantity\": " + quantity + ", \"start\": \"" + start
                        + "\", \"end\": \"" + end + "\"" + named + "}"));
    }

    /** Adds the line and returns its id. */
    protected long line(RequestPostProcessor caller, long draft, long type, int quantity, Instant start, Instant end,
            Long namedResource) throws Exception {
        String body = addLine(caller, draft, type, quantity, start, end, namedResource)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    protected void markOptional(RequestPostProcessor caller, long draft, long line) throws Exception {
        mvc.perform(put("/api/requests/{id}/lines/{line}/required", draft, line).with(caller)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"required\": false}"))
                .andExpect(status().isOk());
    }

    protected ResultActions submit(RequestPostProcessor caller, long request) throws Exception {
        return mvc.perform(post("/api/requests/{id}/submit", request).with(caller));
    }

    protected ResultActions withdraw(RequestPostProcessor caller, long request) throws Exception {
        return mvc.perform(post("/api/requests/{id}/withdraw", request).with(caller));
    }

    protected ResultActions detail(RequestPostProcessor caller, long request) throws Exception {
        return mvc.perform(get("/api/requests/{id}", request).with(caller));
    }

    // ---------------------------------------------------------------- time

    /** Next Tuesday (never today) at this hour, company time. */
    protected Instant tuesdayAt(int hour) {
        return nextTuesday().atTime(LocalTime.of(hour, 0)).atZone(clock.getZone()).toInstant();
    }

    /** The Thursday after next Tuesday at this hour, company time. */
    protected Instant thursdayAt(int hour) {
        return nextTuesday().plusDays(2).atTime(LocalTime.of(hour, 0)).atZone(clock.getZone()).toInstant();
    }

    private LocalDate nextTuesday() {
        return LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.TUESDAY));
    }

    // ---------------------------------------------------------------- rows

    /** The Team's account row, and the same three figures folded from its Transactions and Holds. */
    protected Map<String, Object> account(long team) {
        return jdbc.queryForMap("SELECT granted, earned, held FROM account WHERE team_id = ?", team);
    }

    protected Map<String, Object> foldOfTransactions(long team) {
        return jdbc.queryForMap("""
                SELECT (coalesce(sum(CASE kind WHEN 'CHARGE' THEN -granted_part ELSE granted_part END), 0)
                         - (SELECT coalesce(sum(expired), 0) FROM period_close WHERE team_id = :team))::numeric(12,2) AS granted,
                       coalesce(sum(CASE kind WHEN 'CHARGE' THEN -earned_part ELSE earned_part END), 0)::numeric(12,2) AS earned,
                       (SELECT coalesce(sum(amount), 0) FROM held_credit WHERE team_id = :team)::numeric(12,2) AS held
                FROM transaction WHERE team_id = :team""".replace(":team", Long.toString(team)));
    }

    protected BigDecimal available(long team) {
        return ledger.account(new TeamId(team)).available().amount();
    }

    private void priced(long type, String unitPrice) {
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, ?::numeric)", type, unitPrice);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '12345', '09:00', '17:00', 1.5)", type);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '67', '00:00', '00:00', 0.5)", type);
    }

    private void grant(long team, String amount) {
        ledger.postGrant(new TeamId(team), new PeriodId(period), Credits.of(amount), null, null);
    }
}
