package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Functional test cases of the Exclusive area (docs/test-cases, Section 4.6) through the API, on the test world of
 * {@link DemoWorldTest} and the days of the document's Section 2.
 */
class ExclusiveCasesTest extends DemoWorldTest {

    @Test
    @DisplayName("exc-recurrence-quantity: a weekly or two-room line needs the same free instances for every occurrence, all or none")
    @Verifies("exc-recurrence-quantity")
    void needsTheSameFreeInstancesForEveryOccurrence() throws Exception {
        LocalDate d = dayD();
        // Precondition: oscar has Allocated Requests with Room B on D and Room A on D+7, 10:00-12:00.
        long operations = testData().team(unique("Operations"));
        String oscar = unique("oscar");
        testData().member(operations, oscar, Role.MANAGER);
        ledger.postGrant(new TeamId(operations), new PeriodId(period), Credits.of("1000.00"), null, null);
        for (var booking : new Object[][] {{roomB, d}, {roomA, d.plusDays(7)}}) {
            long request = createDraft(as(oscar));
            line(as(oscar), request, room, 1, at((LocalDate) booking[1], 10), at((LocalDate) booking[1], 12), (Long) booking[0]);
            submit(as(oscar), request).andExpect(jsonPath("$.state").value("ALLOCATED"));
        }

        // 1. L1: Meeting room, quantity 1, 10:00-12:00 weekly on D, D+7, D+14 (Q5 = 90.00), Required. Waiting: Room A
        //    is busy on D+7 and Room B on D, so no single room is free for every occurrence. Nothing held or charged.
        long first = createDraft(as(bob));
        addWeeklyLine(as(bob), first, room, 1, at(d, 10), at(d, 12), null, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recurrence.occurrences").value(3))
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.quote").value(90.00));
        submit(as(bob), first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"));
        detail(as(bob), first)
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(90.00))
                .andExpect(jsonPath("$.lines[0].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1))
                .andExpect(jsonPath("$.lines[0].assignedResources.length()").value(0));
        long l1 = lineOf(first);
        assertNothingAllocatedOrCharged(l1);

        // 2. L2: Meeting room, quantity 1, 10:00-12:00 weekly on D+14, D+21. Allocated, both occurrences on one room.
        long second = createDraft(as(bob));
        long l2 = weeklyLine(as(bob), second, room, 1, at(d.plusDays(14), 10), at(d.plusDays(14), 12), null, 2);
        submit(as(bob), second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(bob), second)
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].assignedResources.length()").value(1))
                .andExpect(jsonPath("$.lines[0].allocatedParts.length()").value(2));
        List<String> rows = jdbc.queryForList("""
                SELECT resource_id || ' ' || state || ' ' || to_char(starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24')
                FROM exclusive_occurrence WHERE line_id = ? ORDER BY starts_at""", String.class, l2);
        assertThat(rows).hasSize(2);
        String instance = rows.getFirst().split(" ")[0];
        assertThat(rows).containsExactly(
                instance + " ALLOCATED " + utcHour(at(d.plusDays(14), 10)),
                instance + " ALLOCATED " + utcHour(at(d.plusDays(21), 10)));
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, l2)).isEqualByComparingTo("60.00");

        // 3. L3: Meeting room, quantity 2, D+14 10:00-12:00. Waiting: L2 holds one of the two rooms on D+14.
        long third = createDraft(as(bob));
        long l3 = line(as(bob), third, room, 2, at(d.plusDays(14), 10), at(d.plusDays(14), 12), null);
        submit(as(bob), third)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"));
        detail(as(bob), third)
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[0].assignedResources.length()").value(0));

        // Pass criteria: no occurrence of any line was allocated alone. Only L2's Charge left Research's Balance.
        assertNothingAllocatedOrCharged(l1);
        assertNothingAllocatedOrCharged(l3);
        assertThat(available(research)).isEqualByComparingTo("540.00");
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    private long lineOf(long request) {
        return jdbc.queryForObject("SELECT id FROM request_line WHERE request_id = ?", Long.class, request);
    }

    private void assertNothingAllocatedOrCharged(long line) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ?", Integer.class, line))
                .as("occurrences of line %d", line).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE line_id = ?", Integer.class, line))
                .as("transactions of line %d", line).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE line_id = ?", Integer.class, line))
                .as("held credits of line %d", line).isZero();
    }

    private static String utcHour(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDateTime().toString().substring(0, 13);
    }
}
