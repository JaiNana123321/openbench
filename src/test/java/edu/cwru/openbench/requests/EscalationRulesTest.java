package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.common.RequestId;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The rules of refusals and escalation (issue #7; design Section 4.8, {@code decideApproval},
 * {@code onWindowLapse}; Scenarios S3 and S4) and their refusals: Refused is final, who decides
 * before and after the lapse, exactly one decision per approval, one escalation notice per lapse,
 * and the Capacity Planner's approval when the capacity was taken meanwhile.
 */
class EscalationRulesTest extends EscalationWorldTest {

    @Test
    @DisplayName("Refused is final: no selection, decision, or withdrawal (WRONG_STATE); open approvals are canceled; nothing is charged")
    void refusedIsFinal() throws Exception {
        LocalDate d = dayD();
        long a = createDraft(as(alice));
        long named = id(addNamedPersonLine(as(alice), a, 1, d, d.plusDays(1), world.dave().id()));
        long skills = personLine(as(alice), a, 1, d.plusDays(7), d.plusDays(8), statistics);
        line(as(alice), a, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), a).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a, skills, world.rita().id()).andExpect(status().isOk());
        long rita = approvalOf(skills);

        decide(as(oscar), approvalOf(named), "REFUSE", "").andExpect(status().isOk());
        detail(as(alice), a).andExpect(jsonPath("$.request.state").value("REFUSED"));
        assertThat(jdbc.queryForObject("SELECT state FROM approval WHERE id = ?", String.class, rita))
                .isEqualTo("CANCELED");
        assertThat(liveRowsOf(a)).isZero();
        assertThat(account(platform)).containsEntry("held", new BigDecimal("0.00"));
        assertThat(transactions(platform, "CHARGE")).isEmpty();

        refused(select(as(alice), a, skills, world.dave().id()), 409, "WRONG_STATE");
        refused(decide(as(bob), rita, "APPROVE", ""), 409, "WRONG_STATE");
        refused(decide(as(erin), rita, "APPROVE", ""), 409, "WRONG_STATE");
        refused(decide(as(alice), rita, "APPROVE", ""), 403, "FORBIDDEN");
        refused(withdraw(as(alice), a), 409, "WRONG_STATE");
        detail(as(alice), a).andExpect(jsonPath("$.request.state").value("REFUSED"));
    }

    @Test
    @DisplayName("Who decides: the lending Manager before the lapse (a Planner is FORBIDDEN), a Planner after (WINDOW_LAPSED for the Manager); one decision only")
    void theLendingManagerBeforeTheLapseAPlannerAfter() throws Exception {
        LocalDate d = dayD();
        long a = createDraft(as(alice));
        long m1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a, m1, world.dave().id()).andExpect(status().isOk());
        long approval = approvalOf(m1);

        refused(decide(as(erin), approval, "APPROVE", ""), 403, "FORBIDDEN");
        escalations(erin).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + a + ")]", empty()));
        escalations(alice).andExpect(status().isForbidden());
        approvals(as(erin)).andExpect(jsonPath("$[?(@.id == " + approval + ")]", empty()));

        lapse(a);
        approvals(as(oscar)).andExpect(jsonPath("$[?(@.id == " + approval + ")]", empty()));
        approvals(as(erin)).andExpect(jsonPath("$[?(@.id == " + approval + ")].state").value("OUTSTANDING"));
        refused(decide(as(oscar), approval, "APPROVE", ""), 409, "WINDOW_LAPSED");
        refused(decide(as(bob), approval, "APPROVE", ""), 403, "FORBIDDEN");

        // Erin refuses: Rita is left, so a replacement is asked and the Request stays Waiting.
        decide(as(erin), approval, "REFUSE", "Dave is needed").andExpect(status().isOk());
        detail(as(alice), a)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("REFUSED"));
        refused(decide(as(erin), approval, "APPROVE", ""), 409, "ALREADY_DECIDED");
        refused(decide(as(oscar), approval, "APPROVE", ""), 409, "ALREADY_DECIDED");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM approval WHERE id = ?", Long.class, approval))
                .isEqualTo(erinId);
    }

    @Test
    @DisplayName("A Planner decides an approval whose window ended before the lapse job ran, and it is marked escalated")
    void aPlannerDecidesAWindowEndedBeforeTheJobRan() throws Exception {
        LocalDate d = dayD();
        long a = createDraft(as(alice));
        long m1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a, m1, world.dave().id()).andExpect(status().isOk());
        movableClock.set(windowEndOf(a).plusSeconds(1));

        decide(as(erin), approvalOf(m1), "APPROVE", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.escalated").value(true));
        detail(as(alice), a).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
    }

    @Test
    @DisplayName("The lapse notifies every Capacity Planner and the requesting Manager once; a second run notifies nobody")
    void theLapseNotifiesOnce() throws Exception {
        LocalDate d = dayD();
        long a = createDraft(as(alice));
        long m1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        submit(as(alice), a).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a, m1, world.dave().id()).andExpect(status().isOk());

        lapse(a);
        lifecycle.onWindowLapse(new RequestId(a));
        assertThat(noticesAbout(erinId, "ESCALATED", a)).hasSize(1);
        assertThat(noticesAbout(world.alice().id(), "ESCALATED", a)).hasSize(1);
        assertThat(noticesAbout(world.oscar().id(), "ESCALATED", a)).isEmpty();
    }

    @Test
    @DisplayName("The Planner's approval when the room was taken meanwhile: Waiting, the line on the waitlist; a Release then allocates it")
    void thePlannersApprovalWaitsForCapacityTakenMeanwhile() throws Exception {
        LocalDate d = dayD();
        long a = createDraft(as(alice));
        long m1 = personLine(as(alice), a, 1, d, d.plusDays(1), statistics);
        long l1 = line(as(alice), a, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), a).andExpect(jsonPath("$.state").value("PENDING"));
        select(as(alice), a, m1, world.dave().id()).andExpect(status().isOk());
        lapse(a);

        // Oscar takes Room A after the lapse released it.
        long o1 = createDraft(as(oscar));
        long ol = line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("ALLOCATED"));

        decide(as(erin), approvalOf(m1), "APPROVE", "").andExpect(status().isOk());
        detail(as(alice), a)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("APPROVED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].waitlistPosition").value(1));
        assertThat(liveRowsOf(a)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan WHERE line_id = ?", Integer.class, m1)).isZero();

        // Oscar releases Room A: A is filled from the waitlist at its locked Quotes, and Dave's Loan forms.
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", o1, ol).with(as(oscar))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());
        detail(as(alice), a)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("LOANED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE line_id = ? AND state = 'ALLOCATED'",
                Integer.class, l1)).isEqualTo(1);
    }

    private static void refused(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
}
