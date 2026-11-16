package edu.cwru.openbench.requests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base of the service-tier tests (issue #23): the Bump test world with helpers for GPU tiers. GPU
 * costs 5.00 per unit-hour, 1.5 times on weekdays 09:00 to 17:00, so 4 units for D 10:00 to 12:00
 * quote 60.00 and 16 units 240.00; its pool holds 16 units.
 */
abstract class TierWorldTest extends BumpWorldTest {

    /** As the Capacity Planner, replaces the type's tiers with this JSON list. */
    protected ResultActions setTiers(long type, String tiers) throws Exception {
        return setSetting("tiers." + type, tiers);
    }

    protected ResultActions setSetting(String key, String valueJson) throws Exception {
        return mvc.perform(put("/api/settings/{key}", key).with(as(erin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + valueJson + "}"));
    }

    /** Defines Standard x1.0 and Express x1.5 for GPU and returns their ids, {standard, express}. */
    protected long[] standardAndExpress() throws Exception {
        String body = setTiers(gpu, "[{\"name\": \"Standard\", \"premium\": 1.0}, {\"name\": \"Express\", \"premium\": 1.5}]")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.pricing['" + gpu + "'].tiers[*].id");
        return new long[] {ids.get(0).longValue(), ids.get(1).longValue()};
    }

    /** Sets GPU's bump cutoff, in hours. */
    protected void gpuBumpCutoff(int hours) throws Exception {
        setSetting("bump_cutoff." + gpu, "\"PT" + hours + "H\"").andExpect(status().isOk());
    }

    /** Adds a line with a tier (null for none). */
    protected ResultActions addTieredLine(RequestPostProcessor caller, long draft, long type, int quantity,
            Instant start, Instant end, Long tier) throws Exception {
        return mvc.perform(post("/api/requests/{id}/lines", draft).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeId\": " + type + ", \"quantity\": " + quantity + ", \"start\": \"" + start
                        + "\", \"end\": \"" + end + "\", \"tierId\": " + tier + "}"));
    }

    /** Adds a GPU line with a tier and returns its id. */
    protected long gpuLine(RequestPostProcessor caller, long draft, int units, Instant start, Instant end, Long tier)
            throws Exception {
        String body = addTieredLine(caller, draft, gpu, units, start, end, tier)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Submits a Request of one GPU line with a tier and returns {request, line}. */
    protected long[] submittedGpu(String manager, int units, Instant start, Instant end, Long tier) throws Exception {
        long draft = createDraft(as(manager));
        long line = gpuLine(as(manager), draft, units, start, end, tier);
        nextSecond();
        submit(as(manager), draft).andExpect(status().isOk());
        return new long[] {draft, line};
    }

    /** The states of the line's Metered rows, by id. */
    protected List<String> meteredStates(long line) {
        return jdbc.queryForList("SELECT state FROM metered_allocation WHERE line_id = ? ORDER BY id", String.class, line);
    }

    /** The most live GPU units in any hour of [start, end). */
    protected int peakLiveUnits(long type, Instant start, Instant end) {
        return jdbc.queryForObject("""
                SELECT coalesce(max(used), 0) FROM (
                  SELECT h, coalesce(sum(m.units), 0) AS used
                  FROM generate_series(?::timestamptz, ?::timestamptz - interval '1 hour', interval '1 hour') AS h
                  LEFT JOIN metered_allocation m ON m.type_id = ? AND m.state IN ('HELD', 'ALLOCATED')
                    AND tstzrange(m.starts_at, m.ends_at, '[)') && tstzrange(h, h + interval '1 hour', '[)')
                  GROUP BY h) hours""", Integer.class,
                Timestamp.from(start), Timestamp.from(end), type);
    }
}
