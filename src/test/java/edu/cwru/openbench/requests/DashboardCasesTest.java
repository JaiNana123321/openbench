package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.MovableClock;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The showback dashboard's test cases (docs/test-cases, Section "Showback Dashboard"), issue #14, on
 * the Matched test world with Room A, Room B, the Confocal microscope, and the GPU pool of 16 units,
 * all owned by Dave Kim, and the test world's Refund Schedule.
 *
 * <p>The cases need day E (a weekday two to six days ahead) and the Loan's E+1 inside the current
 * Period. So the Clock is set, before the worlds are made, to 08:00 on the first day of the Period of
 * today: every figure is then computed from that Clock and that Period, whatever the date of the run,
 * and h, the Period's hours, is 24 times its days (720 for a 30-day month).
 */
class DashboardCasesTest extends LoanEndWorldTest {

    /** Runs before every {@code @BeforeEach}, so the worlds are made on the pinned day. */
    @RegisterExtension
    final BeforeEachCallback startOfThePeriod = context -> {
        ApplicationContext app = SpringExtension.getApplicationContext(context);
        MovableClock clock = app.getBean(MovableClock.class);
        LocalDate today = LocalDate.now(clock);
        LocalDate first = app.getBean(JdbcTemplate.class).queryForObject(
                "SELECT starts_on FROM period WHERE starts_on <= ? AND ends_on >= ?", LocalDate.class, today, today);
        clock.set(first.atTime(8, 0).atZone(clock.getZone()).toInstant());
    };

    private LocalDate e;
    private int hours;

    @BeforeEach
    void daveOwnsTheResourcesAndRefundsFollowTheSchedule() {
        jdbc.update("UPDATE resource SET owner_member_id = ? WHERE type_id IN (?, ?, ?)", world.dave().id(), room,
                microscope, gpu);
        for (long type : List.of(room, microscope, gpu)) {
            jdbc.update("INSERT INTO refund_step (type_id, min_lead_hours, fraction) VALUES (?, 0, 0.25), (?, 168, 0.75)",
                    type, type);
        }
        e = dayE();
        hours = 24 * jdbc.queryForObject("SELECT ends_on - starts_on + 1 FROM period WHERE id = ?", Integer.class, period);
        assertThat(e.plusDays(1)).isBefore(jdbc.queryForObject("SELECT ends_on FROM period WHERE id = ?",
                LocalDate.class, period).plusDays(1));
    }

    @Test
    @DisplayName("dsh-utilization: utilization, idle capacity for seven days, and waiting lines per type; a Release shows at once")
    // Partial: step 2, idle capacity for the Period to date (SRS Dashboard.Utilization), is not built yet.
    @Verifies(value = "dsh-utilization", partial = true)
    void reportsUtilizationIdleAndWaiting() throws Exception {
        long a1 = createDraft(as(alice));
        line(as(alice), a1, room, 1, at(e, 10), at(e, 14), roomA);
        long gpuLine = line(as(alice), a1, gpu, 4, at(e, 10), at(e, 12), null);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        long b1 = createDraft(as(bob));
        line(as(bob), b1, room, 1, at(e, 11), at(e, 12), roomA);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));

        // 1. The current Period: Meeting room 4 / (2 x h), GPU 8 / (16 x h), the microscope 0%; one line
        // waits for the room and none for the GPU.
        String utilization = read(erin, "/api/dashboard/utilization");
        assertThat(figure(utilization, "type", room, "allocatedHours")).isEqualByComparingTo("4");
        assertThat(figure(utilization, "type", room, "availableHours")).isEqualByComparingTo(String.valueOf(2 * hours));
        assertThat(figure(utilization, "type", room, "utilizationPercent")).isEqualByComparingTo(percent(4, 2 * hours));
        assertThat(figure(utilization, "type", gpu, "allocatedHours")).isEqualByComparingTo("8");
        assertThat(figure(utilization, "type", gpu, "utilizationPercent")).isEqualByComparingTo(percent(8, 16 * hours));
        assertThat(figure(utilization, "type", microscope, "utilizationPercent")).isEqualByComparingTo("0");
        if (hours == 720) {
            assertThat(figure(utilization, "type", room, "utilizationPercent")).isEqualByComparingTo("0.28");
            assertThat(figure(utilization, "type", gpu, "utilizationPercent")).isEqualByComparingTo("0.07");
        }
        String waiting = read(erin, "/api/dashboard/waiting");
        assertThat(figure(waiting, "type", room, "waiting")).isEqualByComparingTo("1");
        assertThat(figure(waiting, "type", gpu, "waiting")).isEqualByComparingTo("0");
        assertThat(figure(waiting, "type", microscope, "waiting")).isEqualByComparingTo("0");

        // 2. Idle capacity for the coming seven days: 2 x 168 - 4, 16 x 168 - 8, and 168.
        String idle = read(erin, "/api/dashboard/idle");
        assertThat(figure(idle, "type", room, "idleHours")).isEqualByComparingTo("332");
        assertThat(figure(idle, "type", gpu, "idleHours")).isEqualByComparingTo("2680");
        assertThat(figure(idle, "type", microscope, "idleHours")).isEqualByComparingTo("168");

        // 3. Alice Releases the GPU line: GPU 0% and 2688 idle unit-hours on the next read; the room,
        // allocated and not Released, stays counted.
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", a1, gpuLine).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());
        utilization = read(erin, "/api/dashboard/utilization");
        assertThat(figure(utilization, "type", gpu, "utilizationPercent")).isEqualByComparingTo("0");
        assertThat(figure(utilization, "type", room, "allocatedHours")).isEqualByComparingTo("4");
        idle = read(erin, "/api/dashboard/idle?days=7");
        assertThat(figure(idle, "type", gpu, "idleHours")).isEqualByComparingTo("2688");
        assertThat(figure(idle, "type", room, "idleHours")).isEqualByComparingTo("332");
    }

    @Test
    @DisplayName("dsh-credits: each Team's credits and person-days for the Period agree with its Transactions, Loans, and Statement")
    @Verifies("dsh-credits")
    void reportsTeamCredits() throws Exception {
        platformChargedAndRefunded();
        loanOfDave(e, e.plusDays(1));

        // 1. The Team figures for the current Period.
        String credits = read(erin, "/api/dashboard/credits");
        assertCredits(credits, platform, "5000.00", "30.00", "0.00", "22.50", "0.00", "4992.50", "4992.50");
        assertCredits(credits, research, "600.00", "0.00", "0.00", "0.00", "0.00", "600.00", "600.00");
        assertCredits(credits, operations, "1000.00", "0.00", "800.00", "0.00", "0.00", "1800.00", "1800.00");
        String days = read(erin, "/api/dashboard/person-days");
        assertThat(figure(days, "team", research, "borrowed")).isEqualByComparingTo("2");
        assertThat(figure(days, "team", research, "lent")).isEqualByComparingTo("0");
        assertThat(figure(days, "team", operations, "lent")).isEqualByComparingTo("2");
        assertThat(figure(days, "team", platform, "lent")).isEqualByComparingTo("0");
        assertThat(figure(days, "team", platform, "borrowed")).isEqualByComparingTo("0");

        // 2. Platform's Statement for the Period agrees with the figures.
        String statement = statement(erin, platform).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String kind : List.of("grants", "charges", "earnings", "refunds", "expired")) {
            assertThat(number(statement, "$.totals." + kind)).isEqualByComparingTo(figure(credits, "team", platform, kind));
        }
        assertThat(number(statement, "$.account.balance")).isEqualByComparingTo(figure(credits, "team", platform, "balance"));
        assertThat(number(statement, "$.account.available"))
                .isEqualByComparingTo(figure(credits, "team", platform, "available"));
    }

    @Test
    @DisplayName("dsh-scope: a Manager sees every type and only their Team's credits; a Resource Owner their types and no credits; a Member no dashboard")
    @Verifies("dsh-scope")
    void showsEachRoleItsScope() throws Exception {
        platformChargedAndRefunded();
        loanOfDave(e, e.plusDays(1));

        // 1. Alice: company-wide utilization and idle capacity; credits and Loans for Platform only;
        // Research's figures are not found.
        for (String path : List.of("/api/dashboard/utilization", "/api/dashboard/idle", "/api/dashboard/waiting")) {
            String body = read(alice, path);
            for (long type : List.of(room, microscope, gpu)) {
                assertThat(JsonPath.<List<Object>>read(body, "$[?(@.type == " + type + ")]")).hasSize(1);
            }
        }
        dashboard(alice, "/api/dashboard/credits").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].team").value(platform))
                .andExpect(jsonPath("$[0].balance").value(4992.50));
        dashboard(alice, "/api/dashboard/person-days").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].team").value(platform));
        dashboard(alice, "/api/dashboard/credits?teamId=" + research).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        dashboard(alice, "/api/dashboard/person-days?teamId=" + research).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 2. Dave: utilization and idle capacity of the Resources he owns; no Team credit figures.
        for (String path : List.of("/api/dashboard/utilization", "/api/dashboard/idle", "/api/dashboard/waiting")) {
            assertThat(JsonPath.<List<Integer>>read(read(dave, path), "$[*].type"))
                    .containsExactlyInAnyOrder((int) room, (int) microscope, (int) gpu);
        }
        dashboard(dave, "/api/dashboard/credits").andExpect(status().isForbidden());
        dashboard(dave, "/api/dashboard/person-days").andExpect(status().isForbidden());

        // 3. Ben, a Member with no other role: no dashboard; the API answers FORBIDDEN (design scope table
        // and API table; SRS grants utilization only to Managers, the Planner, and Resource Owners).
        dashboard(ben, "/api/dashboard/utilization").andExpect(status().isForbidden());
        dashboard(ben, "/api/dashboard/idle").andExpect(status().isForbidden());

        // Pass criteria: only the Capacity Planner saw every Team's credits.
        String all = read(erin, "/api/dashboard/credits");
        assertThat(JsonPath.<List<Integer>>read(all, "$[*].team")).contains((int) platform, (int) research, (int) operations);
    }

    // ---------------------------------------------------------------- rules

    @Test
    @DisplayName("The design's worked example: 80 of 192 unit-hours is 41.67% and 112 idle; a Hold and a Released row count nothing")
    void countsOnlyAllocatedHoursClippedToTheWindow() throws Exception {
        long pool = testData().resourceType(unique("Pool"), "METERED");
        testData().resource(pool, unique("pool-node"), world.dave().id(), 8);
        long day = testData().period();
        LocalDate date = jdbc.queryForObject("SELECT starts_on FROM period WHERE id = ?", LocalDate.class, day);
        metered(pool, 4, date, 9, date, 17, "ALLOCATED");
        metered(pool, 2, date, 0, date.plusDays(1), 0, "ALLOCATED");
        metered(pool, 2, date, 14, date, 16, "HELD");
        metered(pool, 6, date, 10, date, 12, "RELEASED");

        String body = read(erin, "/api/dashboard/utilization?periodId=" + day + "&typeId=" + pool);
        assertThat(figure(body, "type", pool, "availableHours")).isEqualByComparingTo("192");
        assertThat(figure(body, "type", pool, "allocatedHours")).isEqualByComparingTo("80");
        assertThat(figure(body, "type", pool, "idleHours")).isEqualByComparingTo("112");
        assertThat(figure(body, "type", pool, "utilizationPercent")).isEqualByComparingTo("41.67");

        // A row across the Period's edges counts only its hours inside: 22:00 the day before to 02:00 after.
        metered(pool, 1, date.minusDays(1), 22, date.plusDays(1), 2, "ALLOCATED");
        body = read(erin, "/api/dashboard/utilization?periodId=" + day + "&typeId=" + pool);
        assertThat(figure(body, "type", pool, "allocatedHours")).isEqualByComparingTo("104");
    }

    @Test
    @DisplayName("Several roles give the union: a Manager who is also a Resource Owner sees every type and their own Team")
    void unitesTheScopesOfSeveralRoles() throws Exception {
        long lab = testData().team(unique("Lab"));
        String lena = unique("lena");
        testData().member(lab, lena, Role.MANAGER, Role.RESOURCE_OWNER);

        String body = read(lena, "/api/dashboard/utilization");
        assertThat(JsonPath.<List<Integer>>read(body, "$[*].type")).contains((int) room, (int) microscope, (int) gpu);
        dashboard(lena, "/api/dashboard/credits").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].team").value(lab))
                .andExpect(jsonPath("$[0].grants").value(0));
    }

    @Test
    @DisplayName("A Resource Owner asking for another owner's type, an unknown type, or an unknown Period gets NOT_FOUND")
    void refusesWhatIsOutOfScopeOrUnknown() throws Exception {
        long others = testData().resourceType(unique("Kiln"), "EXCLUSIVE");
        testData().resource(others, unique("Kiln 1"), world.oscar().id(), 1);
        dashboard(dave, "/api/dashboard/utilization?typeId=" + others).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        dashboard(dave, "/api/dashboard/idle?typeId=" + others).andExpect(status().isNotFound());
        dashboard(dave, "/api/dashboard/waiting?typeId=" + others).andExpect(status().isNotFound());
        dashboard(dave, "/api/dashboard/utilization?typeId=" + room).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        dashboard(erin, "/api/dashboard/utilization?typeId=" + others).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].capacity").value(1));

        dashboard(erin, "/api/dashboard/utilization?typeId=999999999").andExpect(status().isNotFound());
        dashboard(erin, "/api/dashboard/utilization?periodId=999999999").andExpect(status().isNotFound());
        dashboard(erin, "/api/dashboard/credits?periodId=999999999").andExpect(status().isNotFound());
        dashboard(erin, "/api/dashboard/credits?teamId=999999999").andExpect(status().isNotFound());
        dashboard(erin, "/api/dashboard/person-days?periodId=999999999").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Utilization of a Matched type is refused with CLASS_MISMATCH; the idle window must be 1 to 366 days")
    void refusesAMatchedTypeAndABadWindow() throws Exception {
        for (String path : List.of("/api/dashboard/utilization", "/api/dashboard/idle", "/api/dashboard/waiting")) {
            dashboard(erin, path + "?typeId=" + person).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("CLASS_MISMATCH"));
        }
        dashboard(erin, "/api/dashboard/idle?days=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        dashboard(erin, "/api/dashboard/idle?days=367").andExpect(status().isBadRequest());
        dashboard(erin, "/api/dashboard/idle?days=1&typeId=" + room).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableHours").value(48));
    }

    @Test
    @DisplayName("An ended Period shows its closing Balance and expired credits, not the Account now")
    void showsAnEndedPeriodAtItsClose() throws Exception {
        LocalDate endsOn = jdbc.queryForObject("SELECT ends_on FROM period WHERE id = ?", LocalDate.class, period);
        long next = jdbc.queryForObject("SELECT id FROM period WHERE starts_on = ?", Long.class, endsOn.plusDays(1));
        jdbc.update("""
                INSERT INTO period_close (team_id, period_id, expired, closing_granted, closing_earned, closed_at)
                VALUES (?, ?, 1000.00, 4000.00, 0, now())""", platform, period);
        ledger.postGrant(new TeamId(platform), new PeriodId(next),
                Credits.of("200.00"), null, null);
        movableClock.set(endsOn.plusDays(1).atTime(9, 0).atZone(clock.getZone()).toInstant());

        dashboard(erin, "/api/dashboard/credits?periodId=" + period + "&teamId=" + platform)
                .andExpect(jsonPath("$[0].grants").value(5000.00))
                .andExpect(jsonPath("$[0].expired").value(1000.00))
                .andExpect(jsonPath("$[0].balance").value(4000.00))
                .andExpect(jsonPath("$[0].available").value(4000.00))
                .andExpect(jsonPath("$[0].ended").value(true));
        String statement = statement(erin, platform).andReturn().getResponse().getContentAsString();
        assertThat(number(statement, "$.closing.granted")).isEqualByComparingTo("4000.00");

        // The current Period, by default: its own Grant, and the Account as it stands.
        dashboard(alice, "/api/dashboard/credits")
                .andExpect(jsonPath("$[0].period").value(next))
                .andExpect(jsonPath("$[0].grants").value(200.00))
                .andExpect(jsonPath("$[0].balance").value(5200.00))
                .andExpect(jsonPath("$[0].ended").value(false));
    }

    @Test
    @DisplayName("Person-days count the weekdays of a Loan inside the Period, and an early end shortens them and the Earnings")
    void countsPersonDaysInsideThePeriod() throws Exception {
        Formed formed = loanOfDave(e, e.plusDays(1));
        endLoan(as(bob), formed.loan(), e).andExpect(status().isOk());
        LocalDate endsOn = jdbc.queryForObject("SELECT ends_on FROM period WHERE id = ?", LocalDate.class, period);
        testData().loan(carolId, operations, platform, endsOn.minusDays(1), endsOn.plusDays(3));
        int inside = weekdays(endsOn.minusDays(1), endsOn);

        String days = read(erin, "/api/dashboard/person-days");
        assertThat(figure(days, "team", operations, "lent")).isEqualByComparingTo(String.valueOf(1 + inside));
        assertThat(figure(days, "team", research, "borrowed")).isEqualByComparingTo("1");
        assertThat(figure(days, "team", platform, "borrowed")).isEqualByComparingTo(String.valueOf(inside));
        assertThat(figure(read(erin, "/api/dashboard/credits"), "team", operations, "earnings"))
                .isEqualByComparingTo("400.00");
    }

    // ---------------------------------------------------------------- helpers

    /** Platform charged 30.00 for Room A on D 10:00 to 12:00 and was refunded 22.50 for its Release (rel-whole). */
    private void platformChargedAndRefunded() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long l1 = line(as(alice), a1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", a1, l1).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(jsonPath("$.refund.refund").value(22.50));
    }

    private void metered(long type, int units, LocalDate fromDay, int fromHour, LocalDate toDay, int toHour, String state) {
        long request = testData().submittedRequest(platform, at(fromDay, 0));
        long line = testData().meteredLine(request, type, units, at(fromDay, fromHour), at(toDay, toHour));
        jdbc.update("""
                INSERT INTO metered_allocation (line_id, team_id, request_id, type_id, units, starts_at, ends_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", line, platform, request, type, units,
                at(fromDay, fromHour).atOffset(ZoneOffset.UTC), at(toDay, toHour).atOffset(ZoneOffset.UTC),
                state);
    }

    private ResultActions dashboard(String member, String path) throws Exception {
        return mvc.perform(get(path).with(as(member)));
    }

    private String read(String member, String path) throws Exception {
        return dashboard(member, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void assertCredits(String body, long team, String grants, String charges, String earnings, String refunds,
            String expired, String balance, String available) {
        assertThat(figure(body, "team", team, "grants")).isEqualByComparingTo(grants);
        assertThat(figure(body, "team", team, "charges")).isEqualByComparingTo(charges);
        assertThat(figure(body, "team", team, "earnings")).isEqualByComparingTo(earnings);
        assertThat(figure(body, "team", team, "refunds")).isEqualByComparingTo(refunds);
        assertThat(figure(body, "team", team, "expired")).isEqualByComparingTo(expired);
        assertThat(figure(body, "team", team, "balance")).isEqualByComparingTo(balance);
        assertThat(figure(body, "team", team, "available")).isEqualByComparingTo(available);
    }

    /** The field of the one element of a JSON array whose {@code key} is {@code id}. */
    private static BigDecimal figure(String body, String key, long id, String field) {
        List<Object> values = JsonPath.read(body, "$[?(@." + key + " == " + id + ")]." + field);
        assertThat(values).as("%s of %s %d", field, key, id).hasSize(1);
        return new BigDecimal(String.valueOf(values.getFirst()));
    }

    private static BigDecimal number(String body, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, path)));
    }

    /** {@code allocated / available} as a percentage, half-up to 2 decimals. */
    private static BigDecimal percent(int allocated, int available) {
        return BigDecimal.valueOf(allocated * 100L).divide(BigDecimal.valueOf(available), 2, RoundingMode.HALF_UP);
    }

    private static int weekdays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek().getValue() <= 5) {
                count++;
            }
        }
        return count;
    }

    /** Day E: the first weekday at least two days after today whose next day is a weekday too, for the Loan E to E+1. */
    private LocalDate dayE() {
        LocalDate day = LocalDate.now(clock).plusDays(2);
        while (day.getDayOfWeek().getValue() > DayOfWeek.THURSDAY.getValue()) {
            day = day.plusDays(1);
        }
        return day;
    }
}
