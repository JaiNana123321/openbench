package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Weekly recurrence of Exclusive lines (SRS Draft.Line.Add, Exclusive.Conflict): the rules a recurrence obeys, the
 * Quote over every occurrence, edits and splits, the waitlist across weeks, and a whole Release, on the demo world.
 */
class WeeklyLineTest extends ReleaseTest {

    @Test
    @DisplayName("A Metered line cannot recur: 422 RECURRENCE_NOT_OFFERED, on add and on edit")
    void refusesARecurrenceOnAMeteredLine() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        addWeeklyLine(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null, 3)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRENCE_NOT_OFFERED"));
        long line = line(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null);
        editLine(as(alice), draft, line, 4, at(d, 10), at(d, 12), 3)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRENCE_NOT_OFFERED"));
    }

    @Test
    @DisplayName("A weekly line has 2 to 52 occurrences, else 422 RECURRENCE_INVALID")
    void refusesTooFewOrTooManyOccurrences() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        for (int occurrences : List.of(1, 0, 53)) {
            addWeeklyLine(as(alice), draft, room, 1, at(d, 10), at(d, 12), null, occurrences)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        }
        addWeeklyLine(as(alice), draft, room, 1, at(d, 10), at(d, 12), null, 52).andExpect(status().isCreated());
        addWeeklyLine(as(alice), draft, room, 1, at(d, 10), at(d, 12), null, 2).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("A weekly line's window lasts at most one week, else its occurrences would overlap: 422 RECURRENCE_INVALID")
    void refusesAWindowLongerThanAWeek() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        addWeeklyLine(as(alice), draft, room, 1, at(d, 10), at(d.plusDays(7), 11), null, 2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        addWeeklyLine(as(alice), draft, room, 1, at(d, 10), at(d.plusDays(7), 10), null, 2)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Editing a line sets or clears its recurrence, and the Quote follows; a split part keeps it")
    void editsAndSplitsAWeeklyLine() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long line = line(as(alice), draft, room, 2, at(d, 10), at(d, 12), null);

        editLine(as(alice), draft, line, 2, at(d, 10), at(d, 12), 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recurrence.occurrences").value(3))
                .andExpect(jsonPath("$.quote").value(180.00));
        mvc.perform(post("/api/requests/{id}/lines/{line}/split", draft, line).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.recurrence.occurrences").value(3))
                .andExpect(jsonPath("$.quote").value(90.00));

        editLine(as(alice), draft, line, 1, at(d, 10), at(d, 12), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recurrence").isEmpty())
                .andExpect(jsonPath("$.quote").value(30.00));
    }

    @Test
    @DisplayName("Capacity freed in a later week reaches a waiting weekly line, which is then allocated on one room for every week")
    void fillsAWaitingWeeklyLineFromALaterWeek() throws Exception {
        LocalDate d = dayD();
        LocalDate week2 = d.plusDays(7);
        long[] onA = submitted(oscar, room, 1, at(week2, 10), at(week2, 12), roomA);
        submitted(oscar, room, 1, at(week2, 10), at(week2, 12), roomB);
        long b1 = createDraft(as(bob));
        long weekly = weeklyLine(as(bob), b1, room, 1, at(d, 10), at(d, 12), null, 2);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ?", Integer.class, weekly))
                .isZero();

        // Freeing Room A in the second week makes Room A free in both weeks.
        releaseWhole(oscar, onA[0], onA[1]).andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].assignedResources.length()").value(1))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].id").value(roomA))
                .andExpect(jsonPath("$.lines[0].allocatedParts.length()").value(2));
        assertThat(liveOn(roomA, at(d, 10), at(d, 12))).containsExactly(weekly + ":ALLOCATED");
        assertThat(liveOn(roomA, at(week2, 10), at(week2, 12))).containsExactly(weekly + ":ALLOCATED");
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, weekly)).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("Releasing a whole weekly line gives back every occurrence: 3 x 30.00 x 75% = 67.50")
    void releasesEveryOccurrenceOfAWholeWeeklyLine() throws Exception {
        LocalDate d = dayD();
        long request = createDraft(as(alice));
        long weekly = weeklyLine(as(alice), request, room, 1, at(d, 10), at(d, 12), roomA, 3);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("ALLOCATED"));

        release(alice, request, weekly, "{\"kind\": \"WHOLE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line.state").value("RELEASED"))
                .andExpect(jsonPath("$.refund.pieces.length()").value(3))
                .andExpect(jsonPath("$.refund.attributable").value(90.00))
                .andExpect(jsonPath("$.refund.refund").value(67.50));
        assertThat(liveOn(roomA, at(d, 10), at(d.plusDays(14), 12))).isEmpty();
        assertThat(refundsOf(weekly)).containsExactly("67.50/67.50/0.00");
    }

    @Test
    @DisplayName("The database refuses a recurrence on a line that is not Exclusive")
    void theSchemaKeepsRecurrenceToExclusiveLines() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(alice));
        long line = line(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null);
        assertThatThrownBy(() -> jdbc.update("UPDATE request_line SET recurrence = 3 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResultActions editLine(RequestPostProcessor caller, long draft, long line, int quantity, Instant start,
            Instant end, Integer occurrences) throws Exception {
        String recurrence = occurrences == null ? "" : ", \"recurrence\": {\"occurrences\": " + occurrences + "}";
        return mvc.perform(put("/api/requests/{id}/lines/{line}", draft, line).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": " + quantity + ", \"start\": \"" + start + "\", \"end\": \"" + end + "\""
                        + recurrence + "}"));
    }
}
