package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base of the Release tests: the demo world of {@link DemoWorldTest} with the test world's Refund
 * Schedule on every priced type (75% at 168 hours or more before the released part's occurrence
 * starts, 25% below that, 0% once started), and Operations with its Manager Oscar and 1000.00.
 */
abstract class ReleaseTest extends DemoWorldTest {

    protected long operations;
    protected String oscar;

    @BeforeEach
    void addTheRefundScheduleAndOperations() {
        for (long type : List.of(room, microscope, gpu)) {
            jdbc.update("INSERT INTO refund_step (type_id, min_lead_hours, fraction) VALUES (?, 0, 0.25), (?, 168, 0.75)",
                    type, type);
        }
        operations = testData().team(unique("Operations"));
        oscar = unique("oscar");
        testData().member(operations, oscar, Role.MANAGER);
        ledger.postGrant(new TeamId(operations), new PeriodId(period), Credits.of("1000.00"), null, null);
    }

    // ---------------------------------------------------------------- API calls

    /** Submits a new Request with one line and returns {request, line}. */
    protected long[] submitted(String manager, long type, int quantity, Instant start, Instant end, Long named)
            throws Exception {
        long request = createDraft(as(manager));
        long line = line(as(manager), request, type, quantity, start, end, named);
        submit(as(manager), request);
        return new long[] {request, line};
    }

    protected ResultActions release(String manager, long request, long line, String body) throws Exception {
        return mvc.perform(post("/api/requests/{id}/lines/{line}/release", request, line).with(as(manager))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions releaseWhole(String manager, long request, long line) throws Exception {
        return release(manager, request, line, "{\"kind\": \"WHOLE\"}");
    }

    protected ResultActions preview(String manager, long request, long line, String query) throws Exception {
        return mvc.perform(get("/api/requests/{id}/lines/{line}/release-preview?" + query, request, line)
                .with(as(manager)));
    }

    protected ResultActions statement(RequestPostProcessor caller, long team) throws Exception {
        return mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(caller));
    }

    protected String requestState(String manager, long request) throws Exception {
        return JsonPath.read(detail(as(manager), request).andReturn().getResponse().getContentAsString(),
                "$.request.state");
    }

    // ---------------------------------------------------------------- time

    /** Day E of the test case document: a weekday two to six days after today, under seven days ahead. */
    protected LocalDate dayE() {
        LocalDate day = LocalDate.now(clock).plusDays(2);
        while (day.getDayOfWeek().getValue() > 5) {
            day = day.plusDays(1);
        }
        return day;
    }

    // ---------------------------------------------------------------- rows

    protected BigDecimal balance(long team) {
        return ledger.account(new TeamId(team)).balance().amount();
    }

    /** The live (HELD or ALLOCATED) occurrences on the Resource overlapping the window, as "line:state". */
    protected List<String> liveOn(long resource, Instant start, Instant end) {
        return jdbc.queryForList("""
                SELECT line_id || ':' || state FROM exclusive_occurrence
                WHERE resource_id = ? AND state IN ('HELD', 'ALLOCATED') AND starts_at < ? AND ends_at > ?
                ORDER BY starts_at""", String.class, resource, utc(end), utc(start));
    }

    /** The line's Refunds as "amount/granted/earned". */
    protected List<String> refundsOf(long line) {
        return jdbc.queryForList("""
                SELECT amount || '/' || granted_part || '/' || earned_part FROM transaction
                WHERE kind = 'REFUND' AND line_id = ? ORDER BY id""", String.class, line);
    }

    protected static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** The instant as the API writes it: ISO-8601 with the company zone's offset. */
    protected String json(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(clock.getZone()));
    }
}
