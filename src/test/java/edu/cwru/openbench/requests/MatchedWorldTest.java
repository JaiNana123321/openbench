package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.TestData.Login;
import edu.cwru.openbench.TestData.MatchedWorld;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.TeamId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base of the Matched test cases: the test world of {@link DemoWorldTest} (rooms, the microscope, the
 * GPU pool, their prices) with the Matched data of {@code TestData.matchedWorld} in place of its
 * Teams: Platform (Alice Chen, Manager; Ben Ortiz), Research (Bob Patel, Manager; Rita Gomez), and
 * Operations (Oscar Reed, Manager; Dave Kim; Carol Davis), granted 5000.00, 600.00, and 1000.00.
 *
 * <p>Other tests' Members hold the shared skills Statistics, Java, and Microscopy, and the shared
 * database keeps them, so this world's Members hold skills of their own instead, named like the
 * document's (for example {@code Statistics-17}). A Shortlist for {@link #statistics} is then exactly
 * this world's, as the test cases assume. The built-in Person type is the Matched type.
 */
abstract class MatchedWorldTest extends DemoWorldTest {

    protected MatchedWorld world;
    protected long operations;
    protected String oscar;
    protected String dave;
    protected String rita;
    protected String carol;
    protected long carolId;
    protected long statistics;
    protected long java;
    protected long microscopy;
    protected String statisticsName;
    protected long person;

    @BeforeEach
    void createTheMatchedWorld() {
        world = testData().matchedWorld(LocalDate.now(clock));
        platform = world.platform();
        research = world.research();
        operations = world.operations();
        alice = world.alice().username();
        ben = world.ben().username();
        bob = world.bob().username();
        rita = world.rita().username();
        oscar = world.oscar().username();
        dave = world.dave().username();
        carol = unique("carol");
        carolId = testData().member(operations, carol);
        jdbc.update("UPDATE member SET name = 'Carol Davis' WHERE id = ?", carolId);

        statisticsName = unique("Statistics");
        statistics = testData().skill(statisticsName);
        java = testData().skill(unique("Java"));
        microscopy = testData().skill(unique("Microscopy"));
        jdbc.update("DELETE FROM member_skill WHERE member_id IN (?, ?, ?)", world.ben().id(), world.rita().id(),
                world.dave().id());
        testData().holds(world.ben().id(), java);
        testData().holds(world.rita().id(), statistics, microscopy);
        testData().holds(world.dave().id(), statistics);

        person = jdbc.queryForObject("SELECT id FROM resource_type WHERE name = 'Person'", Long.class);
        grant(platform, "5000.00");
        grant(research, "600.00");
        grant(operations, "1000.00");
    }

    // ---------------------------------------------------------------- Person lines

    /** Adds a Person line asking for these skills over the days, inclusive; returns the response. */
    protected ResultActions addPersonLine(RequestPostProcessor caller, long draft, int quantity, LocalDate from,
            LocalDate to, long... skills) throws Exception {
        String ids = Arrays.stream(skills).mapToObj(Long::toString).collect(Collectors.joining(", "));
        return postLine(caller, draft, quantity, from, to, "\"skills\": [" + ids + "]");
    }

    /** Adds a Person line naming one Member over the days, inclusive; returns the response. */
    protected ResultActions addNamedPersonLine(RequestPostProcessor caller, long draft, int quantity, LocalDate from,
            LocalDate to, long member) throws Exception {
        return postLine(caller, draft, quantity, from, to, "\"namedMemberId\": " + member);
    }

    private ResultActions postLine(RequestPostProcessor caller, long draft, int quantity, LocalDate from, LocalDate to,
            String who) throws Exception {
        return mvc.perform(post("/api/requests/{id}/lines", draft).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeId\": " + person + ", \"quantity\": " + quantity + ", \"start\": \"" + midnight(from)
                        + "\", \"end\": \"" + midnight(to.plusDays(1)) + "\", " + who + "}"));
    }

    /** Adds a skill-based Person line and returns its id. */
    protected long personLine(RequestPostProcessor caller, long draft, int quantity, LocalDate from, LocalDate to,
            long... skills) throws Exception {
        return id(addPersonLine(caller, draft, quantity, from, to, skills).andExpect(status().isCreated()));
    }

    protected ResultActions shortlist(RequestPostProcessor caller, long request, long line) throws Exception {
        return mvc.perform(get("/api/requests/{id}/lines/{line}/shortlist", request, line).with(caller));
    }

    protected ResultActions select(RequestPostProcessor caller, long request, long line, long... members)
            throws Exception {
        String ids = Arrays.stream(members).mapToObj(Long::toString).collect(Collectors.joining(", "));
        return mvc.perform(post("/api/requests/{id}/lines/{line}/candidates", request, line).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"members\": [" + ids + "]}"));
    }

    protected ResultActions approvals(RequestPostProcessor caller) throws Exception {
        return mvc.perform(get("/api/approvals").with(caller));
    }

    protected ResultActions decide(RequestPostProcessor caller, long approval, String decision, String reason)
            throws Exception {
        return mvc.perform(post("/api/approvals/{id}/decision", approval).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\": \"" + decision + "\", \"reason\": \"" + reason + "\"}"));
    }

    /** The id of the only approval of the line. */
    protected long approvalOf(long line) {
        return jdbc.queryForObject("""
                SELECT a.id FROM approval a JOIN candidate c ON c.id = a.candidate_id WHERE c.line_id = ?""",
                Long.class, line);
    }

    /** The kinds of the Member's notifications, oldest first. */
    protected List<String> notificationsOf(Login member) {
        return jdbc.queryForList("SELECT kind FROM notification WHERE member_id = ? ORDER BY id", String.class,
                member.id());
    }

    protected Instant midnight(LocalDate day) {
        return day.atStartOfDay(clock.getZone()).toInstant();
    }

    protected static long id(ResultActions created) throws Exception {
        return ((Number) JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void grant(long team, String amount) {
        ledger.postGrant(new TeamId(team), new PeriodId(period), Credits.of(amount), null, null);
    }
}
