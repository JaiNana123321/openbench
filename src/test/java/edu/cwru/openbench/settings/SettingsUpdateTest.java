package edu.cwru.openbench.settings;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Role;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The rules of {@code PUT /api/settings/{key}} (design Section 4.3, the table of changes): each
 * refusal code, with nothing saved and nothing logged; the role check; the keys and body shapes;
 * and the change log. Each test changes only Teams and types it creates; the one global change
 * (the Period length) is put back.
 */
class SettingsUpdateTest extends PostgresTest {

    @Autowired
    private Clock clock;

    private long team;
    private long plannerId;
    private String planner;
    private long room;
    private long gpu;
    private long matched;
    private long currentPeriod;
    private long nextPeriod;

    @BeforeEach
    void createTheWorld() {
        currentPeriod = testData().periodContaining(LocalDate.now(clock));
        nextPeriod = jdbc.queryForObject("""
                SELECT id FROM period WHERE starts_on > (SELECT ends_on FROM period WHERE id = ?)
                ORDER BY starts_on LIMIT 1""", Long.class, currentPeriod);
        team = testData().team(unique("Research"));
        planner = unique("erin");
        plannerId = testData().member(team, planner, Role.PLANNER);
        room = testData().resourceType(unique("Meeting room"), "EXCLUSIVE");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price, bump_cutoff_hours) VALUES (?, 10.00, 24)", room);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '12345', '09:00', '17:00', 1.5)", room);
        jdbc.update("INSERT INTO refund_step (type_id, min_lead_hours, fraction) VALUES (?, 0, 0.25), (?, 168, 0.75)", room, room);
        gpu = testData().resourceType(unique("GPU"), "METERED");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 5.00)", gpu);
        matched = testData().resourceType(unique("Statistician"), "MATCHED");
    }

    // ---------------------------------------------------------------- the log

    @Test
    @DisplayName("Admin.Member.Create: a change is saved, shown at once, and logged with the actor, old and new value, and time")
    void logsEachChange() throws Exception {
        set("unit_price." + room, "12.50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing['" + room + "'].unitPrice").value(12.50));
        set("unit_price." + room, "14").andExpect(status().isOk());

        assertThat(log("unit_price." + room)).containsExactly("10.00>12.50", "12.50>14.00");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_id, at FROM setting_change WHERE key = ? ORDER BY id DESC LIMIT 1", "unit_price." + room);
        assertThat(row.get("actor_id")).isEqualTo(plannerId);
        assertThat(row.get("at")).isNotNull();

        mvc.perform(get("/api/settings/changes").param("size", "2").with(signedInAs(planner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content[0].key").value("unit_price." + room))
                .andExpect(jsonPath("$.content[0].oldValue").value("12.50"))
                .andExpect(jsonPath("$.content[0].newValue").value("14.00"))
                .andExpect(jsonPath("$.content[0].actor").value(plannerId))
                .andExpect(jsonPath("$.content[0].actorName").value(planner))
                .andExpect(jsonPath("$.content[0].at").isString())
                .andExpect(jsonPath("$.content[1].newValue").value("12.50"));
    }

    @Test
    @DisplayName("FORBIDDEN: every role but the Capacity Planner is refused every write and the log, and nothing changes")
    void refusesEveryOtherRole() throws Exception {
        List<String> others = List.of(
                member(Role.ADMIN), member(Role.MANAGER), member(Role.RESOURCE_OWNER), member(Role.MEMBER));
        Map<String, String> writes = Map.of(
                "day_rate", "450.00",
                "unit_price." + room, "20.00",
                "quota." + team + "." + gpu, "4",
                "grant." + team + "." + nextPeriod, "700.00");
        int logged = count("SELECT count(*) FROM setting_change");
        for (String username : others) {
            for (Map.Entry<String, String> write : writes.entrySet()) {
                mvc.perform(put("/api/settings/{key}", write.getKey()).with(signedInAs(username)).with(xsrf())
                                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + write.getValue() + "}"))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
            mvc.perform(get("/api/settings/changes").with(signedInAs(username)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/settings/grants/{period}", nextPeriod).with(signedInAs(username)))
                    .andExpect(status().isForbidden());
        }
        assertThat(count("SELECT count(*) FROM setting_change")).isEqualTo(logged);
        assertThat(count("SELECT count(*) FROM quota WHERE team_id = " + team)).isZero();
        assertThat(count("SELECT count(*) FROM type_pricing WHERE unit_price = 10.00 AND type_id = " + room)).isOne();
    }

    @Test
    @DisplayName("adm-skills-no-policy: the Administrator adds a skill but is refused the settings and a Day Rate change")
    @Verifies("adm-skills-no-policy")
    void givesTheAdministratorNoPolicySetting() throws Exception {
        String carol = member(Role.ADMIN);
        String python = unique("Python");
        String dayRate = value("day_rate");
        int logged = count("SELECT count(*) FROM setting_change");

        // 1. Add a skill: listed, so Members can choose it.
        mvc.perform(post("/api/admin/skills").with(signedInAs(carol)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + python + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/skills").with(signedInAs(planner)))
                .andExpect(jsonPath("$[?(@.name == '%s')].retired", python).value(false));

        // 2. No policy setting is offered (the screens: SkillsPage.test.tsx); the server refuses the settings too.
        mvc.perform(get("/api/settings").with(signedInAs(carol))).andExpect(status().isForbidden());

        // 3. Change the Day Rate through the API: FORBIDDEN.
        mvc.perform(put("/api/settings/day_rate").with(signedInAs(carol)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\": 1.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Pass criteria: the skill list changed and no setting did.
        assertThat(value("day_rate")).isEqualTo(dayRate);
        assertThat(count("SELECT count(*) FROM setting_change")).isEqualTo(logged);
    }

    // ---------------------------------------------------------------- single-valued settings

    @Test
    @DisplayName("VALUE_OUT_OF_RANGE: a premium below 1 and a window under 1 working day are refused, and nothing is saved")
    void refusesAPremiumBelowOneAndAnEmptyWindow() throws Exception {
        String premium = value("priority_premium");
        String window = value("objection_window_working_days");

        refused(set("priority_premium", "0.99"), "VALUE_OUT_OF_RANGE");
        refused(set("objection_window_working_days", "0"), "VALUE_OUT_OF_RANGE");

        assertThat(value("priority_premium")).isEqualTo(premium);
        assertThat(value("objection_window_working_days")).isEqualTo(window);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13})
    @DisplayName("VALUE_OUT_OF_RANGE: a Period length outside 1 to 12 months is refused, and the Periods stay")
    void refusesAPeriodLengthOutOfRange(int months) throws Exception {
        List<Long> periods = jdbc.queryForList("SELECT id FROM period ORDER BY id", Long.class);

        refused(set("period_months", Integer.toString(months)), "VALUE_OUT_OF_RANGE");

        assertThat(jdbc.queryForList("SELECT id FROM period ORDER BY id", Long.class)).isEqualTo(periods);
        assertThat(value("period_months")).isEqualTo("1");
    }

    @Test
    @DisplayName("Planner.Settings: a new Period length rewrites only the Periods not yet started, and a planned Grant keeps its start")
    void rewritesTheFuturePeriods() throws Exception {
        LocalDate first = jdbc.queryForObject("SELECT starts_on FROM period WHERE id = ?", LocalDate.class, nextPeriod);
        long second = jdbc.queryForObject("SELECT id FROM period WHERE starts_on = ?", Long.class, first.plusMonths(1));
        LocalDate current = jdbc.queryForObject("SELECT starts_on FROM period WHERE id = ?", LocalDate.class, currentPeriod);
        testData().grantPlan(team, second, "900.00");
        try {
            set("period_months", "3").andExpect(status().isOk()).andExpect(jsonPath("$.periodMonths").value(3));

            assertThat(jdbc.queryForObject("SELECT starts_on FROM period WHERE id = ?", LocalDate.class, currentPeriod))
                    .as("the current Period is unchanged").isEqualTo(current);
            List<Map<String, Object>> future = jdbc.queryForList("""
                    SELECT starts_on, ends_on FROM period WHERE starts_on >= ? AND starts_on < ? ORDER BY starts_on""",
                    first, first.plusYears(10));
            assertThat(future).hasSize(40);
            assertThat(future.getFirst()).containsEntry("starts_on", java.sql.Date.valueOf(first))
                    .containsEntry("ends_on", java.sql.Date.valueOf(first.plusMonths(3).minusDays(1)));
            // The plan from the second month moves to the first new Period starting on or after it.
            assertThat(jdbc.queryForList("""
                    SELECT p.starts_on || ':' || g.amount FROM grant_plan g JOIN period p ON p.id = g.period_id
                    WHERE g.team_id = ?""", String.class, team))
                    .containsExactly(first.plusMonths(3) + ":900.00");
            assertThat(log("period_months").getLast()).isEqualTo("1>3");
        } finally {
            set("period_months", "1").andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT ends_on FROM period WHERE starts_on = ?", LocalDate.class, first))
                .isEqualTo(first.plusMonths(1).minusDays(1));
        assertThat(count("SELECT count(*) FROM period WHERE starts_on >= '" + first + "' AND starts_on < '"
                + first.plusYears(10) + "'")).isEqualTo(120);
    }

    // ---------------------------------------------------------------- Grants and Quotas

    @Test
    @DisplayName("PERIOD_STARTED: a Grant planned for the current Period is refused; from the next one it is saved and recurs")
    void plansAGrantOnlyForAPeriodNotStarted() throws Exception {
        refused(set("grant." + team + "." + currentPeriod, "700.00"), "PERIOD_STARTED", 409);
        assertNothingLogged("grant." + team + "." + currentPeriod);

        set("grant." + team + "." + nextPeriod, "700.00").andExpect(status().isOk());
        set("grant." + team + "." + nextPeriod, "750.00").andExpect(status().isOk());

        assertThat(log("grant." + team + "." + nextPeriod)).containsExactly("->700.00", "700.00>750.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM grant_plan WHERE team_id = ? AND period_id = ?",
                java.math.BigDecimal.class, team, nextPeriod)).isEqualByComparingTo("750.00");
    }

    @Test
    @DisplayName("VALUE_OUT_OF_RANGE and NOT_FOUND: a negative Grant, an unknown Team, and an unknown Period save nothing")
    void refusesABadGrantPlan() throws Exception {
        refused(set("grant." + team + "." + nextPeriod, "-1.00"), "VALUE_OUT_OF_RANGE");
        refused(set("grant." + Long.MAX_VALUE + "." + nextPeriod, "1.00"), "NOT_FOUND", 404);
        refused(set("grant." + team + "." + Long.MAX_VALUE, "1.00"), "NOT_FOUND", 404);

        assertNothingLogged("grant." + team + "." + nextPeriod);
        assertThat(count("SELECT count(*) FROM grant_plan WHERE team_id = " + team)).isZero();
    }

    @Test
    @DisplayName("Quota: set, changed, and removed with null on a Metered type; each change is logged")
    void setsChangesAndRemovesAQuota() throws Exception {
        String key = "quota." + team + "." + gpu;
        set(key, "10").andExpect(status().isOk()).andExpect(jsonPath("$.quotas['" + team + ":" + gpu + "']").value(10));
        set(key, "6").andExpect(status().isOk());
        set(key, "null").andExpect(status().isOk());
        set(key, "null").andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM quota WHERE team_id = " + team)).isZero();
        assertThat(log(key)).containsExactly("->10", "10>6", "6>-", "->-");
    }

    @Test
    @DisplayName("CLASS_MISMATCH and VALUE_OUT_OF_RANGE: a Quota on an Exclusive type or below 1 unit is refused")
    void refusesABadQuota() throws Exception {
        refused(set("quota." + team + "." + room, "4"), "CLASS_MISMATCH");
        refused(set("quota." + team + "." + gpu, "0"), "VALUE_OUT_OF_RANGE");

        assertNothingLogged("quota." + team + "." + room);
        assertNothingLogged("quota." + team + "." + gpu);
        assertThat(count("SELECT count(*) FROM quota WHERE team_id = " + team)).isZero();
    }

    // ---------------------------------------------------------------- a type's prices

    @Test
    @DisplayName("Unit Price: a first price for an unpriced Metered type is saved; a Matched type and a negative price are refused")
    void setsAUnitPrice() throws Exception {
        long unpriced = testData().resourceType(unique("License"), "METERED");
        set("unit_price." + unpriced, "3.25").andExpect(status().isOk());
        assertThat(log("unit_price." + unpriced)).containsExactly("->3.25");

        refused(set("unit_price." + matched, "10.00"), "CLASS_MISMATCH");
        refused(set("unit_price." + room, "-0.01"), "VALUE_OUT_OF_RANGE");
        refused(set("unit_price." + Long.MAX_VALUE, "1.00"), "NOT_FOUND", 404);

        assertNothingLogged("unit_price." + matched);
        assertNothingLogged("unit_price." + room);
        assertThat(count("SELECT count(*) FROM type_pricing WHERE type_id = " + matched)).isZero();
    }

    @Test
    @DisplayName("Bump cutoff: set on an Exclusive type; a Metered type, a negative or part-hour cutoff, and an unpriced type are refused")
    void setsABumpCutoff() throws Exception {
        set("bump_cutoff." + room, "\"PT48H\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing['" + room + "'].bumpCutoff").value("PT48H"));
        assertThat(log("bump_cutoff." + room)).containsExactly("24 hours>48 hours");

        refused(set("bump_cutoff." + gpu, "\"PT24H\""), "CLASS_MISMATCH");
        refused(set("bump_cutoff." + room, "\"PT-1H\""), "VALUE_OUT_OF_RANGE");
        refused(set("bump_cutoff." + room, "\"PT90M\""), "VALUE_OUT_OF_RANGE");
        long unpriced = testData().resourceType(unique("Lab bench"), "EXCLUSIVE");
        refused(set("bump_cutoff." + unpriced, "\"PT1H\""), "UNIT_PRICE_REQUIRED");

        assertThat(log("bump_cutoff." + room)).hasSize(1);
        assertNothingLogged("bump_cutoff." + gpu);
        assertNothingLogged("bump_cutoff." + unpriced);
        assertThat(jdbc.queryForObject("SELECT bump_cutoff_hours FROM type_pricing WHERE type_id = ?", Integer.class, room))
                .isEqualTo(48);
    }

    @Test
    @DisplayName("Price Bands: the whole list replaces the type's bands, and an empty list removes them")
    void replacesThePriceBands() throws Exception {
        set("price_bands." + room, """
                [{"weekdays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"], "start": "09:00", "end": "17:00", "multiplier": 1.5},
                 {"weekdays": ["SATURDAY", "SUNDAY"], "start": "00:00", "end": "00:00", "multiplier": 0.25},
                 {"weekdays": ["MONDAY"], "start": "17:00", "end": "00:00", "multiplier": 0.5}]""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing['" + room + "'].bands.length()").value(3));
        set("price_bands." + room, "[]").andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM price_band WHERE type_id = " + room)).isZero();
        assertThat(log("price_bands." + room)).containsExactly(
                "Mon Tue Wed Thu Fri 09:00-17:00 x1.5>Mon Tue Wed Thu Fri 09:00-17:00 x1.5; Mon 17:00-24:00 x0.5; Sat Sun 00:00-24:00 x0.25",
                "Mon Tue Wed Thu Fri 09:00-17:00 x1.5; Mon 17:00-24:00 x0.5; Sat Sun 00:00-24:00 x0.25>-");
    }

    @Test
    @DisplayName("BANDS_OVERLAP, BAND_INVALID, CLASS_MISMATCH: refused bands leave the type's bands as they were")
    void refusesBadBands() throws Exception {
        String key = "price_bands." + room;
        refused(set(key, """
                [{"weekdays": ["MONDAY", "TUESDAY"], "start": "09:00", "end": "17:00", "multiplier": 1.5},
                 {"weekdays": ["MONDAY"], "start": "12:00", "end": "18:00", "multiplier": 2.0}]"""), "BANDS_OVERLAP");
        refused(set(key, """
                [{"weekdays": ["SUNDAY"], "start": "20:00", "end": "00:00", "multiplier": 1.5},
                 {"weekdays": ["SUNDAY"], "start": "00:00", "end": "00:00", "multiplier": 2.0}]"""), "BANDS_OVERLAP");
        refused(set(key, "[{\"weekdays\": [], \"start\": \"09:00\", \"end\": \"17:00\", \"multiplier\": 1.5}]"), "BAND_INVALID");
        refused(set(key, "[{\"weekdays\": [\"MONDAY\"], \"start\": \"17:00\", \"end\": \"09:00\", \"multiplier\": 1.5}]"), "BAND_INVALID");
        refused(set(key, "[{\"weekdays\": [\"MONDAY\"], \"start\": \"09:00\", \"end\": \"17:00\", \"multiplier\": 0}]"), "BAND_INVALID");
        refused(set(key, "[{\"weekdays\": [\"MONDAY\"], \"start\": \"09:00\", \"end\": \"17:00\", \"multiplier\": 1000}]"), "BAND_INVALID");
        refused(set("price_bands." + matched, "[]"), "CLASS_MISMATCH");

        assertNothingLogged(key);
        assertThat(jdbc.queryForList("SELECT weekdays || ' ' || start_time || '-' || end_time FROM price_band WHERE type_id = ?",
                String.class, room)).containsExactly("12345 09:00:00-17:00:00");
    }

    @Test
    @DisplayName("Refund Schedule: the whole schedule replaces the type's steps, even with the same lead times")
    void replacesTheRefundSchedule() throws Exception {
        set("refund_steps." + room, "[{\"minLead\": \"PT168H\", \"fraction\": 0.5}, {\"minLead\": \"PT0H\", \"fraction\": 0.1}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing['" + room + "'].refundSchedule.steps[1].fraction").value(0.5));

        assertThat(log("refund_steps." + room)).containsExactly(
                "75% from 168 hours; 25% from 0 hours>50% from 168 hours; 10% from 0 hours");
    }

    @Test
    @DisplayName("REFUND_STEP_TOO_HIGH, VALUE_OUT_OF_RANGE, CLASS_MISMATCH: refused schedules leave the steps as they were")
    void refusesBadRefundSchedules() throws Exception {
        String key = "refund_steps." + room;
        refused(set(key, "[{\"minLead\": \"PT336H\", \"fraction\": 1.0}]"), "REFUND_STEP_TOO_HIGH");
        refused(set(key, "[{\"minLead\": \"PT336H\", \"fraction\": 0.9996}]"), "REFUND_STEP_TOO_HIGH");
        refused(set(key, "[{\"minLead\": \"PT336H\", \"fraction\": -0.1}]"), "VALUE_OUT_OF_RANGE");
        refused(set(key, "[{\"minLead\": \"PT30M\", \"fraction\": 0.5}]"), "VALUE_OUT_OF_RANGE");
        refused(set(key, "[{\"minLead\": \"PT-1H\", \"fraction\": 0.5}]"), "VALUE_OUT_OF_RANGE");
        refused(set(key, "[{\"minLead\": \"PT1H\", \"fraction\": 0.5}, {\"minLead\": \"PT1H\", \"fraction\": 0.4}]"),
                "VALUE_OUT_OF_RANGE");
        refused(set("refund_steps." + matched, "[]"), "CLASS_MISMATCH");

        assertNothingLogged(key);
        assertThat(jdbc.queryForList("SELECT min_lead_hours || ':' || fraction FROM refund_step WHERE type_id = ? ORDER BY 1",
                String.class, room)).containsExactly("0:0.250", "168:0.750");
    }

    // ---------------------------------------------------------------- keys and bodies

    @ParameterizedTest
    @ValueSource(strings = {"exchange_rate", "day_rate.4", "unit_price", "unit_price.x", "grant.1", "quota.1.2.3"})
    @DisplayName("NOT_FOUND: a key that names no setting")
    void refusesAnUnknownKey(String key) throws Exception {
        refused(set(key, "1"), "NOT_FOUND", 404);
    }

    @Test
    @DisplayName("VALIDATION_FAILED: a body without a value, or a value of the wrong shape, is a 400 and saves nothing")
    void refusesAMalformedBody() throws Exception {
        String key = "unit_price." + room;
        refused(mvc.perform(put("/api/settings/{key}", key).with(signedInAs(planner)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 12}")), "VALIDATION_FAILED", 400);
        refused(mvc.perform(put("/api/settings/{key}", key).with(signedInAs(planner)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)), "VALIDATION_FAILED", 400);
        refused(set(key, "\"twelve\""), "VALIDATION_FAILED", 400)
                .andExpect(jsonPath("$.errors[0].field").value("value"));
        refused(set("objection_window_working_days", "2.5"), "VALIDATION_FAILED", 400);
        refused(set("bump_cutoff." + room, "\"two days\""), "VALIDATION_FAILED", 400);
        refused(set("bump_cutoff." + room, "null"), "VALIDATION_FAILED", 400);
        refused(set("price_bands." + room, "[null]"), "VALIDATION_FAILED", 400);
        refused(set("price_bands." + room, "[{\"weekdays\": [\"MONDAY\"]}]"), "VALIDATION_FAILED", 400);
        refused(set("refund_steps." + room, "{\"steps\": []}"), "VALIDATION_FAILED", 400);

        assertNothingLogged(key);
        assertNothingLogged("bump_cutoff." + room);
        assertNothingLogged("price_bands." + room);
        assertNothingLogged("refund_steps." + room);
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions set(String key, String value) throws Exception {
        return mvc.perform(put("/api/settings/{key}", key).with(signedInAs(planner)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + value + "}"));
    }

    private static ResultActions refused(ResultActions call, String code) throws Exception {
        return refused(call, code, 422);
    }

    private static ResultActions refused(ResultActions call, String code, int status) throws Exception {
        return call.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    private String member(Role role) {
        String username = unique(role.name().toLowerCase());
        testData().member(testData().team(unique("Team")), username, role);
        return username;
    }

    private String value(String key) {
        return jdbc.queryForObject("SELECT value FROM planner_setting WHERE key = ?", String.class, key);
    }

    /** The logged changes of the key, oldest first, as "old>new" with "-" for not set. */
    private List<String> log(String key) {
        return jdbc.queryForList("""
                SELECT coalesce(old_value, '-') || '>' || coalesce(new_value, '-') FROM setting_change
                WHERE key = ? ORDER BY id""", String.class, key);
    }

    private void assertNothingLogged(String key) {
        assertThat(log(key)).as("changes logged for %s", key).isEmpty();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
