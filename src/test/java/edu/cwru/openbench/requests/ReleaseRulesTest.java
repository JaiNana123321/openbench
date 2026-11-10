package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules of a Release and of the waitlist fill, one per test, with their refusal codes. */
class ReleaseRulesTest extends ReleaseTest {

    @Test
    @DisplayName("NOT_ALLOCATED: a line already Released cannot be released again; nothing changes")
    void refusesASecondRelease() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        releaseWhole(alice, a1[0], a1[1]).andExpect(status().isOk());

        releaseWhole(alice, a1[0], a1[1])
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        assertThat(refundsOf(a1[1])).hasSize(1);
    }

    @Test
    @DisplayName("NOT_ALLOCATED: a Waiting line holds nothing to release")
    void refusesAWaitingLine() throws Exception {
        LocalDate d = dayD();
        submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long[] b1 = submitted(bob, room, 1, at(d, 10), at(d, 12), roomA);

        releaseWhole(bob, b1[0], b1[1])
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
    }

    @Test
    @DisplayName("NOT_ALLOCATED: an earlier end or an occurrence outside the live allocation is refused; nothing changes")
    void refusesAPartOutsideTheAllocation() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);

        for (String body : List.of(
                "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + at(d, 12) + "\"}",
                "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + at(d, 10) + "\"}",
                "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + at(d, 15) + "\"}",
                "{\"kind\": \"OCCURRENCES\", \"occurrences\": [\"" + at(d.plusDays(7), 10) + "\"]}")) {
            release(alice, a1[0], a1[1], body)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        }
        assertThat(liveOn(roomA, at(d, 10), at(d, 12))).containsExactly(a1[1] + ":ALLOCATED");
        assertThat(refundsOf(a1[1])).isEmpty();
        detail(as(alice), a1[0]).andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"));
    }

    @Test
    @DisplayName("Releasing the one occurrence by its start gives the line back whole")
    void releasesTheOccurrenceByItsStart() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);

        release(alice, a1[0], a1[1], "{\"kind\": \"OCCURRENCES\", \"occurrences\": [\"" + at(d, 10) + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line.state").value("RELEASED"))
                .andExpect(jsonPath("$.refund.refund").value(22.50));
    }

    @Test
    @DisplayName("NOT_FOUND: another Team's Manager cannot release or preview, and a line must be on its Request")
    void hidesOtherTeamsLines() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long[] a2 = submitted(alice, room, 1, at(d, 14), at(d, 16), roomA);

        releaseWhole(bob, a1[0], a1[1]).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        preview(bob, a1[0], a1[1], "kind=WHOLE").andExpect(status().isNotFound());
        releaseWhole(alice, a1[0], a2[1]).andExpect(status().isNotFound());
        releaseWhole(ben, a1[0], a1[1]).andExpect(status().isForbidden());
        assertThat(liveOn(roomA, at(d, 10), at(d, 16))).hasSize(2);
    }

    @Test
    @DisplayName("Malformed parts: no kind or a kind without its field is 400; fewer units of an Exclusive line too; new units must be fewer, and a new end on the hour")
    void refusesMalformedParts() throws Exception {
        LocalDate d = dayD();
        long[] room1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long[] gpus = submitted(alice, gpu, 4, at(d, 10), at(d, 14), null);

        release(alice, room1[0], room1[1], "{}").andExpect(status().isBadRequest());
        release(alice, room1[0], room1[1], "{\"kind\": \"EARLIER_END\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        release(alice, room1[0], room1[1], "{\"kind\": \"FEWER_UNITS\", \"newUnits\": 1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        release(alice, gpus[0], gpus[1], "{\"kind\": \"FEWER_UNITS\", \"newUnits\": 4}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));
        release(alice, gpus[0], gpus[1], "{\"kind\": \"FEWER_UNITS\", \"newUnits\": 0}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));
        release(alice, gpus[0], gpus[1], "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + at(d, 12).plusSeconds(1800) + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WINDOW_INVALID"));
        assertThat(refundsOf(room1[1])).isEmpty();
        assertThat(refundsOf(gpus[1])).isEmpty();
    }

    @Test
    @DisplayName("A partial Release never moves the rest: an earlier end keeps Room A, which another line then gets for the freed hours")
    @Verifies(value = "rel-partial", partial = true)
    void keepsTheRestOnItsInstance() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 14), null);
        long kept = jdbc.queryForObject("SELECT resource_id FROM exclusive_occurrence WHERE line_id = ?", Long.class, a1[1]);

        release(alice, a1[0], a1[1], "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + at(d, 12) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line.state").value("PARTLY_RELEASED"))
                .andExpect(jsonPath("$.refund.refund").value(22.50));

        assertThat(jdbc.queryForList("""
                SELECT resource_id || ' ' || state FROM exclusive_occurrence WHERE line_id = ? ORDER BY starts_at""",
                String.class, a1[1])).containsExactly(kept + " ALLOCATED", kept + " RELEASED");
        detail(as(alice), a1[0])
                .andExpect(jsonPath("$.lines[0].assignedResources[0].id").value(kept))
                .andExpect(jsonPath("$.lines[0].allocatedParts[0].window.end").value(json(at(d, 12))));
        long[] b1 = submitted(bob, room, 1, at(d, 12), at(d, 14), kept);
        assertThat(requestState(bob, b1[0])).isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("The preview shows the Refund a Release would post and changes nothing")
    void previewsTheRefund() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, gpu, 4, at(d, 10), at(d, 14), null);

        preview(alice, a1[0], a1[1], "kind=EARLIER_END&newEnd=" + at(d, 12))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributable").value(60.00))
                .andExpect(jsonPath("$.refund").value(45.00))
                .andExpect(jsonPath("$.pieces[0].fraction").value(0.75))
                .andExpect(jsonPath("$.pieces[0].amount").value(4));
        preview(alice, a1[0], a1[1], "kind=FEWER_UNITS&newUnits=3").andExpect(jsonPath("$.refund").value(22.50));
        preview(alice, a1[0], a1[1], "kind=OCCURRENCES&occurrences=" + at(d, 10)).andExpect(jsonPath("$.refund").value(90.00));
        // Several occurrences go comma-separated, as the web client sends them; D+7 is not this line's.
        preview(alice, a1[0], a1[1], "kind=OCCURRENCES&occurrences=" + at(d, 10) + "," + at(d.plusDays(7), 10))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        preview(alice, a1[0], a1[1], "kind=EARLIER_END").andExpect(status().isBadRequest());
        preview(alice, a1[0], a1[1], "kind=FEWER_UNITS&newUnits=9")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));

        detail(as(alice), a1[0]).andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].allocatedParts[0].amount").value(4));
        assertThat(refundsOf(a1[1])).isEmpty();
    }

    @Test
    @DisplayName("Lifecycle.Waiting.Reevaluate: a waiting Request its Team can no longer afford keeps its place; the next one is allocated")
    void skipsAnUnaffordableWaitingRequest() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long[] b1 = submitted(bob, room, 1, at(d, 10), at(d, 12), roomA);
        long[] o1 = submitted(oscar, room, 1, at(d, 10), at(d, 12), roomA);
        // Research's Available Balance drops below B1's locked Quote of 30.00.
        ledger.holdCredits(new TeamId(research), new RequestLineId(testData().requestLine(research)), Credits.of("580.00"));

        releaseWhole(alice, a1[0], a1[1]).andExpect(status().isOk());

        detail(as(bob), b1[0])
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
        assertThat(requestState(oscar, o1[0])).isEqualTo("ALLOCATED");
        assertThat(available(research)).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Lifecycle.Allocate in a fill: an Optional line that no longer fits is Dropped with its reason; the Required lines are allocated")
    void dropsAnOptionalLineThatNoLongerFits() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long b1 = createDraft(as(bob));
        long required = line(as(bob), b1, room, 1, at(d, 10), at(d, 12), roomA);
        long optional = line(as(bob), b1, gpu, 4, at(d, 10), at(d, 12), null);
        markOptional(as(bob), b1, optional);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
        detail(as(bob), b1).andExpect(jsonPath("$.lines[1].line.state").value("EVALUATED"));
        submitted(oscar, gpu, 16, at(d, 10), at(d, 12), null);
        BigDecimal before = available(research);

        releaseWhole(alice, a1[0], a1[1]).andExpect(status().isOk());

        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("DROPPED"))
                .andExpect(jsonPath("$.lines[1].reason").value("POOL_FULL"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE line_id = ?", Integer.class, optional)).isZero();
        assertThat(available(research)).isEqualByComparingTo(before.subtract(new BigDecimal("30.00")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE line_id = ?", Integer.class, required)).isEqualTo(1);
    }
}
