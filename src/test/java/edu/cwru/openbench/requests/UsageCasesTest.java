package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.MovableClock;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Role;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * Actual usage of Metered allocations (issue #24): the cases met-usage-record and dsh-usage, and the
 * rules of recording and comparing, on the Matched test world with the GPU pool of 16 units owned by
 * Dave Kim.
 *
 * <p>Usage is recorded only once the Period containing the allocation has ended, and Periods may not
 * overlap in the shared database. So, as in {@link DashboardCasesTest}, the Clock is set, before the
 * worlds are made, to 08:00 on the first day of the Period of today; Alice's GPU line for day E (a
 * weekday two to six days ahead, inside that Period) is allocated and charged through the API; and
 * the Clock then moves to the morning after the Period's last day, when the Period has ended.
 */
class UsageCasesTest extends LoanEndWorldTest {

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
    private LocalDate endsOn;

    @BeforeEach
    void daveOwnsTheGpuPool() {
        jdbc.update("UPDATE resource SET owner_member_id = ? WHERE type_id = ?", world.dave().id(), gpu);
        e = dayE();
        endsOn = jdbc.queryForObject("SELECT ends_on FROM period WHERE id = ?", LocalDate.class, period);
        assertThat(e.plusDays(1)).isBeforeOrEqualTo(endsOn);
    }

    @Test
    @DisplayName("met-usage-record: Dave records 3 of Alice's 4 GPU units after the Period; the Charge stays 60.00 and no Transaction is added")
    @Verifies("met-usage-record")
    void recordsTheUnitsUsedWithNoLedgerEffect() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();
        Ledger before = ledgerOf(platform);
        endThePeriod();

        // 1. The usage list for the ended Period lists Alice's allocation with 4 units requested.
        String list = read(dave, "/api/usage?periodId=" + period);
        assertThat(JsonPath.<List<Object>>read(list, "$[?(@.id == " + allocation + ")]")).hasSize(1);
        assertThat(field(list, allocation, "units")).isEqualTo(4);
        assertThat(field(list, allocation, "team.id")).isEqualTo((int) platform);
        assertThat(field(list, allocation, "type")).isEqualTo((int) gpu);
        assertThat(new BigDecimal(String.valueOf(field(list, allocation, "requestedUnitHours"))))
                .isEqualByComparingTo("8");
        assertThat(field(list, allocation, "usedUnits")).isNull();
        assertThat(field(list, allocation, "recordable")).isEqualTo(true);

        // 2. Dave records 3 units: saved for that allocation.
        record(dave, allocation, "3").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(allocation))
                .andExpect(jsonPath("$.usedUnits").value(3))
                .andExpect(jsonPath("$.recordedBy").value("Dave Kim"));
        assertThat(jdbc.queryForMap("SELECT used_units, usage_recorded_by FROM metered_allocation WHERE id = ?",
                allocation)).containsEntry("used_units", 3).containsEntry("usage_recorded_by", world.dave().id());
        assertThat(field(read(dave, "/api/usage?periodId=" + period), allocation, "usedUnits")).isEqualTo(3);

        // 3. Alice's Statement for the Period: the Charge is still 60.00 and no Transaction was added.
        statement(alice, platform).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.charges").value(60.00))
                .andExpect(jsonPath("$.transactions.length()").value(before.transactions()));
        assertThat(ledgerOf(platform)).isEqualTo(before);
    }

    @Test
    @DisplayName("dsh-usage: the Planner sees GPU / Platform requested 8 unit-hours and used 6; the Statement is unchanged")
    @Verifies("dsh-usage")
    void comparesRequestedWithUsedUnitHours() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();
        Ledger before = ledgerOf(platform);
        endThePeriod();
        record(dave, allocation, "3").andExpect(status().isOk());

        // 1. The dashboard for the ended Period: GPU, Platform requested 8 unit-hours and used 6.
        String usage = read(erin, "/api/dashboard/usage?periodId=" + period);
        Map<String, Object> row = usageRow(usage, platform, gpu);
        assertThat(new BigDecimal(String.valueOf(row.get("requestedUnitHours")))).isEqualByComparingTo("8");
        assertThat(new BigDecimal(String.valueOf(row.get("usedUnitHours")))).isEqualByComparingTo("6");
        assertThat(row).containsEntry("recorded", 1).containsEntry("unrecorded", 0)
                .containsEntry("period", (int) period);

        // 2. Platform's Statement for the Period is unchanged: the Charge is 60.00.
        statement(erin, platform).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.charges").value(60.00));
        assertThat(ledgerOf(platform)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- recording rules

    @Test
    @DisplayName("Units used below 0 or above the allocation's units are refused with VALIDATION_FAILED; 0 and all of them are accepted")
    void refusesUnitsOutsideZeroToTheAllocation() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();
        endThePeriod();

        record(dave, allocation, "-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        record(dave, allocation, "5").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        record(dave, allocation, "null").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(usedUnits(allocation)).isNull();

        record(dave, allocation, "0").andExpect(status().isOk()).andExpect(jsonPath("$.usedUnits").value(0));
        record(dave, allocation, "4").andExpect(status().isOk()).andExpect(jsonPath("$.usedUnits").value(4));
    }

    @Test
    @DisplayName("Recording again replaces the value and keeps who recorded it last and when")
    void recordingAgainReplacesTheValue() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();
        endThePeriod();
        record(dave, allocation, "3").andExpect(status().isOk());

        String other = unique("rex");
        long rex = testData().member(operations, other, Role.RESOURCE_OWNER);
        testData().resource(gpu, unique("gpu-node-c"), rex, 4);
        Instant later = movableClock.instant().plusSeconds(3600);
        movableClock.set(later);
        record(other, allocation, "2").andExpect(status().isOk())
                .andExpect(jsonPath("$.usedUnits").value(2));

        assertThat(usedUnits(allocation)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT usage_recorded_by FROM metered_allocation WHERE id = ?", Long.class,
                allocation)).isEqualTo(rex);
        assertThat(jdbc.queryForObject("SELECT usage_recorded_at FROM metered_allocation WHERE id = ?",
                OffsetDateTime.class, allocation).toInstant()).isEqualTo(later);
    }

    @Test
    @DisplayName("Usage cannot be recorded until the Period containing the allocation has ended (WRONG_STATE), even after its window")
    void refusesBeforeThePeriodEnds() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();

        record(dave, allocation, "3").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
        movableClock.set(at(e.plusDays(1), 9));
        record(dave, allocation, "3").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
        assertThat(field(read(dave, "/api/usage?periodId=" + period), allocation, "recordable")).isEqualTo(false);
        movableClock.set(at(endsOn, 23));
        record(dave, allocation, "3").andExpect(status().isConflict());

        endThePeriod();
        record(dave, allocation, "3").andExpect(status().isOk());
    }

    @Test
    @DisplayName("A Released allocation is not listed, and recording on it is refused with WRONG_STATE")
    void refusesARowThatIsNotAllocated() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();
        jdbc.update("UPDATE metered_allocation SET state = 'RELEASED' WHERE id = ?", allocation);
        endThePeriod();

        assertThat(JsonPath.<List<Object>>read(read(dave, "/api/usage?periodId=" + period),
                "$[?(@.id == " + allocation + ")]")).isEmpty();
        record(dave, allocation, "3").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
    }

    @Test
    @DisplayName("Another Resource Owner, or one whose Resource of the type is retired, gets NOT_FOUND, and the list leaves the allocation out")
    void refusesAnOwnerOfOtherTypes() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();
        endThePeriod();

        String kilnOwner = unique("kay");
        long kay = testData().member(operations, kilnOwner, Role.RESOURCE_OWNER);
        testData().resource(testData().resourceType(unique("Kiln"), "EXCLUSIVE"), unique("Kiln 1"), kay, 1);
        String retiredOwner = unique("ray");
        long ray = testData().member(operations, retiredOwner, Role.RESOURCE_OWNER);
        long retired = testData().resource(gpu, unique("gpu-node-old"), ray, 2);
        jdbc.update("UPDATE resource SET retired_at = now() WHERE id = ?", retired);

        for (String owner : List.of(kilnOwner, retiredOwner)) {
            record(owner, allocation, "3").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            assertThat(JsonPath.<List<Object>>read(read(owner, "/api/usage?periodId=" + period),
                    "$[?(@.id == " + allocation + ")]")).isEmpty();
        }
        record(dave, 999_999_999L, "3").andExpect(status().isNotFound());
        mvc.perform(get("/api/usage?periodId=999999999").with(as(dave))).andExpect(status().isNotFound());
        assertThat(usedUnits(allocation)).isNull();
    }

    @Test
    @DisplayName("A Manager or a plain Member may not record usage or list it (403), and a plain Member may not read the comparison")
    void refusesOtherRoles() throws Exception {
        long allocation = aliceUsesFourGpuUnitsOnE();
        endThePeriod();

        for (String member : List.of(alice, ben, erin)) {
            record(member, allocation, "3").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(get("/api/usage?periodId=" + period).with(as(member))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/dashboard/usage?periodId=" + period).with(as(ben))).andExpect(status().isForbidden());
        assertThat(usedUnits(allocation)).isNull();
    }

    @Test
    @DisplayName("The hourly check of the pool ignores used_units: a pool full by units stays full though little was used")
    void theHourlyCheckIgnoresUsedUnits() throws Exception {
        long a1 = createDraft(as(alice));
        line(as(alice), a1, gpu, 16, at(e, 10), at(e, 12), null);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        long full = jdbc.queryForObject("""
                SELECT m.id FROM metered_allocation m JOIN request_line l ON l.id = m.line_id
                WHERE l.request_id = ? AND m.state = 'ALLOCATED'""", Long.class, a1);
        testData().usedUnits(full, 0, world.dave().id());

        long b1 = createDraft(as(bob));
        line(as(bob), b1, gpu, 1, at(e, 11), at(e, 12), null);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
    }

    // ---------------------------------------------------------------- comparison rules

    @Test
    @DisplayName("The comparison clips each window to the Period, counts only recorded allocations, and counts the unrecorded ones")
    void comparesOnlyRecordedHoursInsideThePeriod() throws Exception {
        LocalDate first = jdbc.queryForObject("SELECT starts_on FROM period WHERE id = ?", LocalDate.class, period);
        // 2 units from 22:00 the day before the Period to 02:00 on its first day, 1 used: only the hours
        // from midnight count, which are 2, or 3 when the Period starts on the day summer time ends.
        long across = testData().meteredAllocation(research, gpu, 2, at(first.minusDays(1), 22), at(first, 2));
        testData().usedUnits(across, 1, world.dave().id());
        long inside = Duration.between(at(first, 0), at(first, 2)).toHours();
        long whole = testData().meteredAllocation(research, gpu, 3, at(e, 9), at(e, 13));
        testData().usedUnits(whole, 3, world.dave().id());
        testData().meteredAllocation(research, gpu, 5, at(e, 14), at(e, 16));

        Map<String, Object> row = usageRow(read(erin, "/api/dashboard/usage?periodId=" + period), research, gpu);
        // Requested: 2 x inside + 3 x 4; used: 1 x inside + 3 x 4; the 5-unit allocation is unrecorded.
        assertThat(new BigDecimal(String.valueOf(row.get("requestedUnitHours"))))
                .isEqualByComparingTo(BigDecimal.valueOf(2 * inside + 12));
        assertThat(new BigDecimal(String.valueOf(row.get("usedUnitHours"))))
                .isEqualByComparingTo(BigDecimal.valueOf(inside + 12));
        assertThat(row).containsEntry("recorded", 2).containsEntry("unrecorded", 1);
    }

    @Test
    @DisplayName("Comparison scope: the Planner every Team, a Manager their own Team, a Resource Owner the types they own")
    void showsEachRoleItsScope() throws Exception {
        testData().usedUnits(testData().meteredAllocation(platform, gpu, 4, at(e, 10), at(e, 12)), 3,
                world.dave().id());
        testData().usedUnits(testData().meteredAllocation(research, gpu, 2, at(e, 10), at(e, 12)), 2,
                world.dave().id());
        String path = "/api/dashboard/usage?periodId=" + period;

        assertThat(teamsOn(read(erin, path), gpu)).containsExactlyInAnyOrder((int) platform, (int) research);
        assertThat(teamsOn(read(dave, path), gpu)).containsExactlyInAnyOrder((int) platform, (int) research);
        String aliceSees = read(alice, path);
        assertThat(teamsOn(aliceSees, gpu)).containsExactly((int) platform);
        assertThat(JsonPath.<List<Integer>>read(aliceSees, "$[*].team")).containsOnly((int) platform);
        assertThat(teamsOn(read(bob, path), gpu)).containsExactly((int) research);

        String kilnOwner = unique("kay");
        long kay = testData().member(operations, kilnOwner, Role.RESOURCE_OWNER);
        testData().resource(testData().resourceType(unique("Kiln"), "EXCLUSIVE"), unique("Kiln 1"), kay, 1);
        assertThat(teamsOn(read(kilnOwner, path), gpu)).isEmpty();

        // With no Period named, the current one.
        assertThat(teamsOn(read(erin, "/api/dashboard/usage"), gpu)).hasSize(2);
        mvc.perform(get("/api/dashboard/usage?periodId=999999999").with(as(erin))).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    /** Alice's Request A1: 4 GPU units for E 10:00 to 12:00, allocated and charged 60.00; returns the allocation. */
    private long aliceUsesFourGpuUnitsOnE() throws Exception {
        long a1 = createDraft(as(alice));
        long gpuLine = line(as(alice), a1, gpu, 4, at(e, 10), at(e, 12), null);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        statement(alice, platform).andExpect(jsonPath("$.totals.charges").value(60.00));
        return jdbc.queryForObject("SELECT id FROM metered_allocation WHERE line_id = ? AND state = 'ALLOCATED'",
                Long.class, gpuLine);
    }

    /** The morning after the Period's last day: the Period has ended. */
    private void endThePeriod() {
        movableClock.set(at(endsOn.plusDays(1), 9));
    }

    private ResultActions record(String member, long allocation, String usedUnits) throws Exception {
        return mvc.perform(put("/api/usage/{id}", allocation).with(as(member))
                .contentType(MediaType.APPLICATION_JSON).content("{\"usedUnits\": " + usedUnits + "}"));
    }

    private String read(String member, String path) throws Exception {
        return mvc.perform(get(path).with(as(member))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Integer usedUnits(long allocation) {
        return jdbc.queryForObject("SELECT used_units FROM metered_allocation WHERE id = ?", Integer.class, allocation);
    }

    /** A field of the listed allocation with this id. */
    private static Object field(String body, long allocation, String path) {
        List<Object> values = JsonPath.read(body, "$[?(@.id == " + allocation + ")]." + path);
        assertThat(values).as("%s of allocation %d", path, allocation).hasSize(1);
        return values.getFirst();
    }

    private static Map<String, Object> usageRow(String body, long team, long type) {
        List<Map<String, Object>> rows = JsonPath.read(body, "$[?(@.team == " + team + " && @.type == " + type + ")]");
        assertThat(rows).as("the row of Team %d and type %d", team, type).hasSize(1);
        return rows.getFirst();
    }

    private static List<Integer> teamsOn(String body, long type) {
        return JsonPath.read(body, "$[?(@.type == " + type + ")].team");
    }

    /** What the Ledger holds for the Team: its Transactions, their sum, its Account, and the Quotes of its lines. */
    private record Ledger(int transactions, BigDecimal total, Map<String, Object> account, BigDecimal quotes) {}

    private Ledger ledgerOf(long team) {
        return new Ledger(
                jdbc.queryForObject("SELECT count(*) FROM transaction WHERE team_id = ?", Integer.class, team),
                jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM transaction WHERE team_id = ?",
                        BigDecimal.class, team),
                account(team),
                jdbc.queryForObject("""
                        SELECT coalesce(sum(l.locked_quote), 0) FROM request_line l JOIN request r ON r.id = l.request_id
                        WHERE r.team_id = ?""", BigDecimal.class, team));
    }

    /** Day E: the first weekday at least two days after today whose next day is a weekday too. */
    private LocalDate dayE() {
        LocalDate day = LocalDate.now(clock).plusDays(2);
        while (day.getDayOfWeek().getValue() > DayOfWeek.THURSDAY.getValue()) {
            day = day.plusDays(1);
        }
        return day;
    }
}
