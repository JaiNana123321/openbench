package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Functional test cases of the Capacity Planner's settings (docs/test-cases, Section "Administration")
 * through the API, on the demo world of {@link ReleaseTest} with the test world's bump cutoff of 24
 * hours on the Exclusive types and Erin Walsh as the Capacity Planner. The single-valued settings are
 * global, so adm-planner-settings puts them back when it ends.
 */
class SettingsCasesTest extends ReleaseTest {

    private static final List<String> GLOBAL_KEYS = List.of("day_rate", "priority_premium", "objection_window_working_days");

    private long nextPeriod;

    @BeforeEach
    void completeTheTestWorld() {
        jdbc.update("UPDATE type_pricing SET bump_cutoff_hours = 24 WHERE type_id IN (?, ?)", room, microscope);
        jdbc.update("UPDATE member SET name = 'Erin Walsh' WHERE username = ?", erin);
        nextPeriod = jdbc.queryForObject("""
                SELECT id FROM period WHERE starts_on > (SELECT ends_on FROM period WHERE id = ?)
                ORDER BY starts_on LIMIT 1""", Long.class, period);
    }

    @Test
    @DisplayName("adm-planner-settings: each policy value is saved as data, shown at once, and logged; refused values save nothing")
    @Verifies("adm-planner-settings")
    void setsEachPolicyValueAsData() throws Exception {
        Map<String, String> original = globalSettings();
        try {
            // 1. Change each value: saved without a restart and shown at once.
            set("day_rate", "450.00").andExpect(status().isOk()).andExpect(jsonPath("$.dayRate").value(450.00));
            set("priority_premium", "3").andExpect(status().isOk());
            set("objection_window_working_days", "3").andExpect(status().isOk());
            set("bump_cutoff." + room, "\"PT48H\"").andExpect(status().isOk());
            set("quota." + platform + "." + gpu, "10").andExpect(status().isOk());
            set("grant." + research + "." + nextPeriod, "700.00").andExpect(status().isOk());
            set("price_bands." + gpu, bands(WEEKDAYS_AT_1_5, weekendAt("0.25"))).andExpect(status().isOk());
            set("refund_steps." + room, steps("0.5", "0.1")).andExpect(status().isOk());

            String roomPricing = "$.pricing['" + room + "']";
            String gpuPricing = "$.pricing['" + gpu + "']";
            mvc.perform(get("/api/settings").with(as(erin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dayRate").value(450.00))
                    .andExpect(jsonPath("$.priorityPremium").value(3))
                    .andExpect(jsonPath("$.objectionWindowWorkingDays").value(3))
                    .andExpect(jsonPath(roomPricing + ".bumpCutoff").value("PT48H"))
                    .andExpect(jsonPath("$.quotas['" + platform + ":" + gpu + "']").value(10))
                    .andExpect(jsonPath(gpuPricing + ".bands[?(@.start == '00:00:00')].multiplier").value(0.25))
                    .andExpect(jsonPath(gpuPricing + ".bands[?(@.start == '00:00:00')].weekdays[*]",
                            containsInAnyOrder("SATURDAY", "SUNDAY")))
                    .andExpect(jsonPath(roomPricing + ".refundSchedule.steps[0].minLead").value("PT0S"))
                    .andExpect(jsonPath(roomPricing + ".refundSchedule.steps[0].fraction").value(0.1))
                    .andExpect(jsonPath(roomPricing + ".refundSchedule.steps[1].minLead").value("PT168H"))
                    .andExpect(jsonPath(roomPricing + ".refundSchedule.steps[1].fraction").value(0.5));
            mvc.perform(get("/api/settings/grants/{period}", nextPeriod).with(as(erin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$['" + research + "']").value(700.00));

            // 2. The history: one entry per change, newest first, by Erin Walsh, with the old and new value and the time.
            ResultActions history = mvc.perform(get("/api/settings/changes").param("size", "8").with(as(erin)))
                    .andExpect(status().isOk());
            List<String[]> expected = List.of(
                    new String[] {"refund_steps." + room, "75% from 168 hours; 25% from 0 hours", "50% from 168 hours; 10% from 0 hours"},
                    new String[] {"price_bands." + gpu, "Mon Tue Wed Thu Fri 09:00-17:00 x1.5; Sat Sun 00:00-24:00 x0.5",
                            "Mon Tue Wed Thu Fri 09:00-17:00 x1.5; Sat Sun 00:00-24:00 x0.25"},
                    new String[] {"grant." + research + "." + nextPeriod, null, "700.00"},
                    new String[] {"quota." + platform + "." + gpu, null, "10"},
                    new String[] {"bump_cutoff." + room, "24 hours", "48 hours"},
                    new String[] {"objection_window_working_days", original.get("objection_window_working_days"), "3"},
                    new String[] {"priority_premium", original.get("priority_premium"), "3"},
                    new String[] {"day_rate", original.get("day_rate"), "450.00"});
            for (int i = 0; i < expected.size(); i++) {
                String entry = "$.content[" + i + "]";
                history.andExpect(jsonPath(entry + ".key").value(expected.get(i)[0]))
                        .andExpect(jsonPath(entry + ".oldValue").value(expected.get(i)[1]))
                        .andExpect(jsonPath(entry + ".newValue").value(expected.get(i)[2]))
                        .andExpect(jsonPath(entry + ".actorName").value("Erin Walsh"))
                        .andExpect(jsonPath(entry + ".at").isString());
            }
            int logged = loggedChanges();

            // 3. A Price Band overlapping an existing one: BANDS_OVERLAP, nothing saved.
            String monday = "{\"weekdays\": [\"MONDAY\"], \"start\": \"12:00\", \"end\": \"18:00\", \"multiplier\": 2.0}";
            set("price_bands." + gpu, bands(WEEKDAYS_AT_1_5, weekendAt("0.25"), monday))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BANDS_OVERLAP"));
            assertThat(jdbc.queryForList("SELECT weekdays || ':' || multiplier FROM price_band WHERE type_id = ? ORDER BY weekdays",
                    String.class, gpu)).containsExactly("12345:1.500", "67:0.250");

            // 4. A Refund Schedule step of 100%: REFUND_STEP_TOO_HIGH, nothing saved.
            set("refund_steps." + room, "[{\"minLead\": \"PT336H\", \"fraction\": 1.0}, "
                    + "{\"minLead\": \"PT168H\", \"fraction\": 0.5}, {\"minLead\": \"PT0H\", \"fraction\": 0.1}]")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("REFUND_STEP_TOO_HIGH"));
            assertThat(jdbc.queryForList("SELECT min_lead_hours || ':' || fraction FROM refund_step WHERE type_id = ? ORDER BY min_lead_hours",
                    String.class, room)).containsExactly("0:0.100", "168:0.500");

            // 5. A negative Day Rate: VALUE_OUT_OF_RANGE, nothing saved; a Day Rate of 0.00 is allowed.
            set("day_rate", "-1.00")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALUE_OUT_OF_RANGE"));
            assertThat(globalSettings()).containsEntry("day_rate", "450.00");
            assertThat(loggedChanges()).as("refused changes are not logged").isEqualTo(logged);
            set("day_rate", "0.00").andExpect(status().isOk()).andExpect(jsonPath("$.dayRate").value(0.00));

            // Pass criteria: only valid values were saved, and every saved change is in the history.
            assertThat(loggedChanges()).isEqualTo(logged + 1);
        } finally {
            original.forEach((key, value) -> jdbc.update("UPDATE planner_setting SET value = ? WHERE key = ?", value, key));
        }
    }

    @Test
    @DisplayName("adm-settings-effect: a changed Refund Schedule, Grant plan, and Unit Price affect only what comes after; no posted Transaction or locked Quote changes")
    @Verifies("adm-settings-effect")
    void affectsOnlyWhatComesAfter() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long a2 = createDraft(as(alice));
        line(as(alice), a2, room, 1, at(d, 10), at(d, 12), roomB);
        detail(as(alice), a1[0]).andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(30.00));

        // 1. Change the Refund Schedule: saved.
        set("refund_steps." + room, steps("0.5", "0.1")).andExpect(status().isOk());

        // 2. As alice, Release A1's line: Refund 15.00 (30.00 x 0.50), since the Release is after the change.
        releaseWhole(alice, a1[0], a1[1])
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.refund").value(15.00))
                .andExpect(jsonPath("$.refund.pieces[0].fraction").value(0.5));

        // 3. Platform's planned Grant: refused for the current Period, saved from the next; the posted 5000.00 stays.
        set("grant." + platform + "." + period, "6000.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_STARTED"));
        set("grant." + platform + "." + nextPeriod, "6000.00").andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT amount FROM transaction WHERE kind = 'GRANT' AND team_id = ?",
                BigDecimal.class, platform)).containsExactly(new BigDecimal("5000.00"));
        assertThat(jdbc.queryForList("SELECT period_id || ':' || amount FROM grant_plan WHERE team_id = ?",
                String.class, platform)).containsExactly(nextPeriod + ":6000.00");

        // 4. Unit Price of Meeting room 20.00: A2's Quote is 60.00; A1's locked Quote is still 30.00.
        set("unit_price." + room, "20.00").andExpect(status().isOk());
        mvc.perform(get("/api/requests/{id}/draft", a2).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quote").value(60.00));
        detail(as(alice), a1[0]).andExpect(jsonPath("$.lines[0].line.quote").value(30.00));

        // Pass criteria: no posted Transaction or locked Quote changed.
        assertThat(jdbc.queryForList("SELECT kind || ':' || amount FROM transaction WHERE line_id = ? ORDER BY id",
                String.class, a1[1])).containsExactly("CHARGE:30.00", "REFUND:15.00");
        assertThat(jdbc.queryForObject("SELECT locked_quote FROM request_line WHERE id = ?", BigDecimal.class, a1[1]))
                .isEqualByComparingTo("30.00");
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
    }

    // ---------------------------------------------------------------- helpers

    private static final String WEEKDAYS_AT_1_5 = "{\"weekdays\": [\"MONDAY\", \"TUESDAY\", \"WEDNESDAY\", \"THURSDAY\", "
            + "\"FRIDAY\"], \"start\": \"09:00\", \"end\": \"17:00\", \"multiplier\": 1.5}";

    private static String weekendAt(String multiplier) {
        return "{\"weekdays\": [\"SATURDAY\", \"SUNDAY\"], \"start\": \"00:00\", \"end\": \"00:00\", \"multiplier\": "
                + multiplier + "}";
    }

    private static String bands(String... bands) {
        return "[" + String.join(", ", bands) + "]";
    }

    /** The test cases' Refund Schedule: {@code atSevenDays} at 168 hours or more, {@code below} under that. */
    private static String steps(String atSevenDays, String below) {
        return "[{\"minLead\": \"PT168H\", \"fraction\": " + atSevenDays + "}, {\"minLead\": \"PT0H\", \"fraction\": "
                + below + "}]";
    }

    private ResultActions set(String key, String value) throws Exception {
        return mvc.perform(put("/api/settings/{key}", key).with(as(erin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + value + "}"));
    }

    private Map<String, String> globalSettings() {
        Map<String, String> values = new java.util.HashMap<>();
        for (String key : GLOBAL_KEYS) {
            values.put(key, jdbc.queryForObject("SELECT value FROM planner_setting WHERE key = ?", String.class, key));
        }
        return values;
    }

    private int loggedChanges() {
        return jdbc.queryForObject("SELECT count(*) FROM setting_change", Integer.class);
    }
}
