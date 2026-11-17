package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Bump test cases of {@code docs/test-cases/sections/cases/06-exclusive.tex} (SRS
 * Exclusive.Priority.Bump, Exclusive.Bump.Effect), step by step through the API.
 */
class BumpCasesTest extends BumpWorldTest {

    @Test
    @DisplayName("exc-bump: a Priority line Bumps the latest-submitted blocking standard line, and its Charge is Q4 = 60.00")
    @Verifies("exc-bump")
    void bumpsTheLatestSubmittedBlockingLine() throws Exception {
        LocalDate d = dayD();
        // Preconditions: B1 (bob) has Room A and, submitted second, O1 (oscar) has Room B, D 10:00-12:00.
        long[] b1 = submitted(bob, room, at(d, 10), at(d, 12), roomA, false);
        long[] o1 = submitted(oscar, room, at(d, 10), at(d, 12), roomB, false);

        // 1. Add L1, Meeting room, D 10:00-12:00, Priority, to a Draft: Quote 60.00, twice the standard Quote.
        long a = createDraft(as(alice));
        long l1 = line(as(alice), a, room, 1, at(d, 10), at(d, 12), null);
        setPriority(as(alice), a, l1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote").value(60.00));

        // 2. Submit: Allocated with L1 on Room B; O1's line is Bumped (one Bump each, O1 submitted later).
        nextSecond();
        submit(as(alice), a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(alice), a)
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].id").value(roomB));
        detail(as(oscar), o1[0])
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("BUMPED"));
        detail(as(bob), b1[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].id").value(roomA));

        // 3. Room A's and Room B's timelines for D: Room A, B1 unchanged; Room B, Platform's Priority line L1.
        timeline(erin, roomA, at(d, 10), at(d, 12))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].state").value("ALLOCATED"))
                .andExpect(jsonPath("$[0].request").value(b1[0]))
                .andExpect(jsonPath("$[0].priority").value(false));
        timeline(erin, roomB, at(d, 10), at(d, 12))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].state").value("ALLOCATED"))
                .andExpect(jsonPath("$[0].team.id").value(platform))
                .andExpect(jsonPath("$[0].request").value(a))
                .andExpect(jsonPath("$[0].priority").value(true));

        // Pass criteria: exactly one line was Bumped, the latest-submitted; the Charge for L1 is 60.00.
        assertThat(rowStates(o1[1])).containsExactly("BUMPED");
        assertThat(rowStates(b1[1])).containsExactly("ALLOCATED");
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, l1)).isEqualByComparingTo("60.00");
        assertThat(refundsOf(b1[1])).isEmpty();
    }

    @Test
    @DisplayName("exc-bump-effect: the Bumped line is refunded in full, heads its waitlist, and its Request is Waiting")
    @Verifies("exc-bump-effect")
    void refundsTheBumpedLineAndPutsItFirst() throws Exception {
        LocalDate d = dayD();
        // Preconditions: B1 (bob) Room A; O1 (oscar) Room B (30.00) and GPU 4 units (60.00), both Required,
        // both D 10:00-12:00 and Allocated; then Alice's Priority line L1 Bumps O1's room line.
        submitted(bob, room, at(d, 10), at(d, 12), roomA, false);
        long o1 = createDraft(as(oscar));
        long roomLine = line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomB);
        long gpuLine = line(as(oscar), o1, gpu, 4, at(d, 10), at(d, 12), null);
        nextSecond();
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        assertThat(available(operations)).isEqualByComparingTo("910.00");
        long[] a = submitted(alice, room, at(d, 10), at(d, 12), null, true);
        detail(as(alice), a[0]).andExpect(jsonPath("$.request.state").value("ALLOCATED"));

        // 1. Oscar's notifications: his room line was Bumped, with the reason, a Priority line.
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).singleElement().asString()
                .contains("Line " + roomLine, "of Request " + o1, "Bumped by a Priority line", "Full Refund of 30.00",
                        "position 1 on the waitlist");

        // 2. O1 is Waiting; the room line is Bumped and first on the waitlist for Meeting room, D 10:00-12:00;
        // the GPU line is still allocated. The history records the Bump with both lines, the actor, and the time.
        detail(as(oscar), o1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.id").value(roomLine))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("BUMPED"))
                .andExpect(jsonPath("$.lines[0].reasonMessage").value(containsString("Bumped by a Priority line")))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1))
                .andExpect(jsonPath("$.lines[1].line.id").value(gpuLine))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.history[?(@.line == " + roomLine + " && @.toState == 'BUMPED')].fromState")
                        .value(hasItem("ALLOCATED")))
                .andExpect(jsonPath("$.history[?(@.line == " + roomLine + " && @.toState == 'BUMPED')].actor")
                        .value(hasItem((int) world.alice().id())))
                .andExpect(jsonPath("$.history[?(@.line == " + roomLine + " && @.toState == 'BUMPED')].at")
                        .value(hasItem(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(clock.instant().atZone(clock.getZone())))))
                .andExpect(jsonPath("$.history[?(@.line == " + roomLine + " && @.toState == 'BUMPED')].note")
                        .value(hasItem(allOf(containsString("Priority line " + a[1] + " of Request " + a[0]),
                                containsString("full Refund of 30.00 credits")))))
                .andExpect(jsonPath("$.history[?(@.line == null && @.toState == 'WAITING')].fromState")
                        .value(hasItem("ALLOCATED")));
        detail(as(alice), a[0])
                .andExpect(jsonPath("$.history[?(@.line == " + a[1] + " && @.toState == 'ALLOCATED')].note")
                        .value(hasItem(containsString("Bumped line " + roomLine + " of Request " + o1))));
        assertThat(waitlistEntryOf(roomLine)).containsExactly("true/false");

        // 3. The Statement of Operations: a Refund of 30.00 on the room line's Charge; the GPU Charge of 60.00
        // untouched; Balance 940.00 (1000.00 - 30.00 - 60.00 + 30.00).
        statement(oscar, operations)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'REFUND')].line").value((int) roomLine))
                .andExpect(jsonPath("$.totals.refunds").value(30.00))
                .andExpect(jsonPath("$.account.balance").value(940.00));
        assertThat(refundsOf(roomLine)).containsExactly("30.00");
        assertThat(refundsOf(gpuLine)).isEmpty();
        assertThat(rowStates(roomLine)).containsExactly("BUMPED");
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));

        // 4. Withdrawing O1 is refused while the GPU line is still allocated (#27); nothing changes.
        Map<String, Object> before = account(operations);
        withdraw(as(oscar), o1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"));
        detail(as(oscar), o1).andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));
        assertThat(waitlistEntryOf(roomLine)).containsExactly("true/false");
        assertThat(account(operations)).isEqualTo(before);
    }

    @Test
    @DisplayName("exc-bump-cutoff: no Bump inside the bump cutoff; the Priority line waits ahead of standard lines")
    @Verifies("exc-bump-cutoff")
    void doesNotBumpInsideTheCutoff() throws Exception {
        Instant h = hourH();
        Instant h2 = h.plusSeconds(2 * 3600);
        // Preconditions: bob's Room A and oscar's Room B for H to H+2, Allocated; bob's standard line waits.
        long[] bobs = submitted(bob, room, h, h2, roomA, false);
        long[] oscars = submitted(oscar, room, h, h2, roomB, false);
        long[] bobWaiting = submitted(bob, room, h, h2, null, false);
        detail(as(bob), bobWaiting[0]).andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));

        // 1. Submit L1, Meeting room, H to H+2, Priority: no Bump, Waiting, L1 at position 1.
        long[] l1 = submitted(alice, room, h, h2, null, true);
        detail(as(alice), l1[0])
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
        detail(as(bob), bobWaiting[0]).andExpect(jsonPath("$.lines[0].waitlistPosition").value(2));
        assertThat(waitlistEntryOf(l1[1])).containsExactly("false/true");

        // Pass criteria: both blocking lines are still allocated.
        assertThat(rowStates(bobs[1])).containsExactly("ALLOCATED");
        assertThat(rowStates(oscars[1])).containsExactly("ALLOCATED");
        detail(as(oscar), oscars[0]).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        assertThat(refundsOf(bobs[1])).isEmpty();
        assertThat(refundsOf(oscars[1])).isEmpty();
    }

    @Test
    @DisplayName("exc-priority-protected: a Priority line is never Bumped and never Bumps another Priority line")
    @Verifies("exc-priority-protected")
    void neverBumpsAPriorityLine() throws Exception {
        LocalDate d = dayD();
        // Preconditions: alice's and oscar's Allocated Priority lines on Room A and Room B, D 10:00-12:00.
        long[] alices = submitted(alice, room, at(d, 10), at(d, 12), roomA, true);
        long[] oscars = submitted(oscar, room, at(d, 10), at(d, 12), roomB, true);

        // 1. bob submits L1, Meeting room, D 10:00-12:00, Priority: no Bump; Waiting with L1 on the waitlist.
        long[] l1 = submitted(bob, room, at(d, 10), at(d, 12), null, true);
        detail(as(bob), l1[0])
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));

        // Pass criteria: Alice's and Oscar's lines are still allocated and no Refund was posted.
        detail(as(alice), alices[0]).andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"));
        detail(as(oscar), oscars[0]).andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"));
        assertThat(rowStates(alices[1])).containsExactly("ALLOCATED");
        assertThat(rowStates(oscars[1])).containsExactly("ALLOCATED");
        assertThat(refundsOf(alices[1])).isEmpty();
        assertThat(refundsOf(oscars[1])).isEmpty();
        assertThat(noticesOf(world.alice().id(), "BUMPED")).isEmpty();
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).isEmpty();
    }
}
