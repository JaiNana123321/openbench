package edu.cwru.openbench.settings;

import static edu.cwru.openbench.TestData.unique;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Role;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SettingsControllerTest extends PostgresTest {

    @Autowired
    private Clock clock;

    private long team;
    private String planner;
    private String manager;
    private String member;

    @BeforeEach
    void createMembers() {
        testData().periodContaining(LocalDate.now(clock));
        team = testData().team(unique("Operations"));
        planner = unique("erin");
        testData().member(team, planner, Role.PLANNER);
        manager = unique("oscar");
        testData().member(team, manager, Role.MANAGER);
        member = unique("dave");
        testData().member(team, member);
    }

    @Test
    void showsEverySettingToThePlanner() throws Exception {
        long room = jdbc.queryForObject("INSERT INTO resource_type (name, allocation_class) VALUES (?, 'EXCLUSIVE') RETURNING id",
                Long.class, unique("Meeting room"));
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price, bump_cutoff_hours) VALUES (?, 10.00, 24)", room);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '67', '00:00', '00:00', 0.5)", room);
        jdbc.update("INSERT INTO refund_step (type_id, min_lead_hours, fraction) VALUES (?, 168, 0.75)", room);
        jdbc.update("INSERT INTO quota (team_id, type_id, max_units) VALUES (?, ?, 8)", team, room);

        String pricing = "$.pricing['" + room + "']";
        mvc.perform(get("/api/settings").with(signedInAs(planner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayRate").value(400.00))
                .andExpect(jsonPath("$.priorityPremium").value(2.0))
                .andExpect(jsonPath("$.objectionWindowWorkingDays").value(2))
                .andExpect(jsonPath("$.currentPeriod.startsOn").isString())
                .andExpect(jsonPath(pricing + ".unitPrice").value(10.00))
                .andExpect(jsonPath(pricing + ".bumpCutoff").value("PT24H"))
                .andExpect(jsonPath(pricing + ".bands[0].weekdays.length()").value(2))
                .andExpect(jsonPath(pricing + ".bands[0].start").value("00:00:00"))
                .andExpect(jsonPath(pricing + ".bands[0].multiplier").value(0.5))
                .andExpect(jsonPath(pricing + ".refundSchedule.steps[0].minLead").value("PT168H"))
                .andExpect(jsonPath(pricing + ".refundSchedule.steps[0].fraction").value(0.75))
                .andExpect(jsonPath("$.quotas['" + team + ":" + room + "']").value(8));
    }

    @Test
    void refusesTheSettingsToAManager() throws Exception {
        mvc.perform(get("/api/settings").with(signedInAs(manager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void listsThePeriodsToAnyMember() throws Exception {
        long period = testData().period();
        LocalDate day = jdbc.queryForObject("SELECT starts_on FROM period WHERE id = ?", LocalDate.class, period);

        mvc.perform(get("/api/settings/periods").with(signedInAs(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].startsOn", period).value(day.toString()))
                .andExpect(jsonPath("$[?(@.id == %d)].endsOn", period).value(day.toString()));
    }
}
