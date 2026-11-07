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
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Functional test cases of the Exclusive area (docs/test-cases, Section 4.6) through the API, on the test world of
 * {@link DemoWorldTest} and the days of the document's Section 2.
 */
class ExclusiveCasesTest extends DemoWorldTest {

    /**
     * Steps 1 and 2 need lines with a weekly recurrence, which the build cannot express yet (no API field and no
     * {@code recurrence} column), so step 3 runs with L2's occurrence on D+14, the one that blocks L3.
     */
    @Test
    @DisplayName("exc-recurrence-quantity, step 3: a line for two rooms waits while one is taken on D+14; nothing is held or charged")
    @Verifies(value = "exc-recurrence-quantity", partial = true)
    void waitsForTwoRoomsWhenOneIsTaken() throws Exception {
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

        // L2's occurrence on D+14: Allocated.
        long second = createDraft(as(bob));
        line(as(bob), second, room, 1, at(d.plusDays(14), 10), at(d.plusDays(14), 12), null);
        submit(as(bob), second).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));

        // 3. L3: Meeting room, quantity 2, D+14 10:00-12:00. Waiting: the line takes both rooms or none.
        long third = createDraft(as(bob));
        long l3 = line(as(bob), third, room, 2, at(d.plusDays(14), 10), at(d.plusDays(14), 12), null);
        submit(as(bob), third)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"));
        detail(as(bob), third)
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[0].assignedResources.length()").value(0));

        // Pass criteria: no occurrence of any line was allocated alone.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ?", Integer.class, l3))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE line_id = ?", Integer.class, l3)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE line_id = ?", Integer.class, l3)).isZero();
        assertThat(available(research)).isEqualByComparingTo("570.00");
    }
}
