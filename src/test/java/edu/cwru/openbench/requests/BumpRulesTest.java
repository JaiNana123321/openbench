package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.allocation.AllocationLock;
import edu.cwru.openbench.allocation.AllocatorRegistry;
import edu.cwru.openbench.allocation.BumpPlan;
import edu.cwru.openbench.allocation.Bumpable;
import edu.cwru.openbench.allocation.LineSnapshot;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Bump rules beyond the test cases (SRS Exclusive.Priority.Bump, Exclusive.Bump.Effect; design
 * Section 4.5, "Bump plan"): the cutoff over every occurrence of a weekly line, a line Bumped whole,
 * the fewest Bumps, a Held line, an Optional line, the lines that never Bump, and a stale plan.
 */
class BumpRulesTest extends BumpWorldTest {

    @Autowired
    private AllocatorRegistry allocators;

    @Autowired
    private AllocationLock typeLock;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("No Bump when the blocking weekly line has another occurrence inside the cutoff, though the overlapping one is outside it")
    void noBumpWhenAnyOccurrenceIsInsideTheCutoff() throws Exception {
        Instant h = hourH();
        Instant week = h.atZone(clock.getZone()).plusWeeks(1).toInstant();
        long draft = createDraft(as(oscar));
        long weekly = weeklyLine(as(oscar), draft, room, 1, h, h.plusSeconds(7200), roomA, 3);
        nextSecond();
        submit(as(oscar), draft).andExpect(jsonPath("$.state").value("ALLOCATED"));

        // Alice's Priority line asks for Room A in the second week only, a week outside the cutoff.
        long[] priority = submitted(alice, room, week, week.plusSeconds(7200), roomA, true);

        detail(as(alice), priority[0]).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(rowStates(weekly)).containsOnly("ALLOCATED").hasSize(3);
        assertThat(refundsOf(weekly)).isEmpty();
    }

    @Test
    @DisplayName("A Bumped line is Bumped whole: every occurrence of a weekly line, with a full Refund of its Charge (Q5 = 90.00)")
    void bumpsAWeeklyLineWhole() throws Exception {
        LocalDate d = dayD();
        long draft = createDraft(as(oscar));
        long weekly = weeklyLine(as(oscar), draft, room, 1, at(d, 10), at(d, 12), roomA, 3);
        nextSecond();
        submit(as(oscar), draft).andExpect(jsonPath("$.state").value("ALLOCATED"));

        // Alice's Priority line overlaps only the second occurrence, D+7.
        long[] priority = submitted(alice, room, at(d.plusDays(7), 10), at(d.plusDays(7), 12), roomA, true);

        detail(as(alice), priority[0]).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        assertThat(rowStates(weekly)).containsExactly("BUMPED", "BUMPED", "BUMPED");
        assertThat(refundsOf(weekly)).containsExactly("90.00");
        detail(as(oscar), draft)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].allocatedParts.length()").value(0));
        // D and D+14 are free again; D+7 is the Priority line's.
        timeline(erin, roomA, at(d, 10), at(d, 12)).andExpect(jsonPath("$[0].state").value("FREE"));
        timeline(erin, roomA, at(d.plusDays(14), 10), at(d.plusDays(14), 12))
                .andExpect(jsonPath("$[0].state").value("FREE"));
        timeline(erin, roomA, at(d.plusDays(7), 10), at(d.plusDays(7), 12))
                .andExpect(jsonPath("$[0].request").value(priority[0]));
    }

    @Test
    @DisplayName("Fewest Bumps: one Bump on Room B beats two on Room A, though Room A's lines were submitted later")
    void bumpsTheFewestLines() throws Exception {
        LocalDate d = dayD();
        long[] wide = submitted(bob, room, at(d, 9), at(d, 13), roomB, false);
        long[] early = submitted(oscar, room, at(d, 9), at(d, 11), roomA, false);
        long[] late = submitted(oscar, room, at(d, 11), at(d, 13), roomA, false);

        long[] priority = submitted(alice, room, at(d, 10), at(d, 12), null, true);

        detail(as(alice), priority[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].id").value(roomB));
        assertThat(rowStates(wide[1])).containsExactly("BUMPED");
        assertThat(rowStates(early[1])).containsExactly("ALLOCATED");
        assertThat(rowStates(late[1])).containsExactly("ALLOCATED");
    }

    @Test
    @DisplayName("A Held line is Bumped: its held credits come back and no Refund posts; its Pending Request waits and releases its other Holds")
    void bumpsAHeldLine() throws Exception {
        LocalDate d = dayD();
        // Oscar's Request is Pending on a Person line, with Room B (30.00) and 4 GPU units (60.00) on Hold.
        long o1 = createDraft(as(oscar));
        personLine(as(oscar), o1, 1, d, d.plusDays(1), statistics);
        long roomLine = line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomB);
        long gpuLine = line(as(oscar), o1, gpu, 4, at(d, 10), at(d, 12), null);
        nextSecond();
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("PENDING"));
        assertThat(available(operations)).isEqualByComparingTo("910.00");

        long[] priority = submitted(alice, room, at(d, 10), at(d, 12), roomB, true);

        detail(as(alice), priority[0]).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        assertThat(rowStates(roomLine)).containsExactly("BUMPED");
        assertThat(jdbc.queryForList("SELECT state FROM metered_allocation WHERE line_id = ?", String.class, gpuLine))
                .containsExactly("EXPIRED");
        assertThat(refundsOf(roomLine)).isEmpty();
        assertThat(available(operations)).isEqualByComparingTo("1000.00");
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
        detail(as(oscar), o1)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].reason").value("BUMPED"))
                .andExpect(jsonPath("$.lines[1].waitlistPosition").value(1))
                .andExpect(jsonPath("$.lines[2].line.state").value("EVALUATED"))
                .andExpect(jsonPath("$.history[-1].fromState").value("PENDING"))
                .andExpect(jsonPath("$.history[-1].toState").value("WAITING"));
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).singleElement().asString()
                .contains("held credits were released", "position 1 on the waitlist");
    }

    @Test
    @DisplayName("A Held Priority line is never Bumped either")
    void neverBumpsAHeldPriorityLine() throws Exception {
        LocalDate d = dayD();
        long o1 = createDraft(as(oscar));
        personLine(as(oscar), o1, 1, d, d.plusDays(1), statistics);
        long roomLine = line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomB);
        setPriority(as(oscar), o1, roomLine, true).andExpect(status().isOk());
        nextSecond();
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("PENDING"));

        long[] priority = submitted(alice, room, at(d, 10), at(d, 12), roomB, true);

        detail(as(alice), priority[0]).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(rowStates(roomLine)).containsExactly("HELD");
        detail(as(oscar), o1).andExpect(jsonPath("$.request.state").value("PENDING"));
    }

    @Test
    @DisplayName("A Bumped Optional line is Dropped with its reason and a full Refund; its Request stays Allocated")
    void dropsABumpedOptionalLine() throws Exception {
        LocalDate d = dayD();
        long o1 = createDraft(as(oscar));
        long roomLine = line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomB);
        long gpuLine = line(as(oscar), o1, gpu, 4, at(d, 10), at(d, 12), null);
        markOptional(as(oscar), o1, roomLine);
        nextSecond();
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("ALLOCATED"));

        submitted(alice, room, at(d, 10), at(d, 12), roomB, true);

        detail(as(oscar), o1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("DROPPED"))
                .andExpect(jsonPath("$.lines[0].reason").value("BUMPED"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty())
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));
        assertThat(waitlistEntryOf(roomLine)).isEmpty();
        assertThat(refundsOf(roomLine)).containsExactly("30.00");
        assertThat(refundsOf(gpuLine)).isEmpty();
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).singleElement().asString()
                .contains("Full Refund of 30.00", "it was Optional, so it was Dropped");
    }

    @Test
    @DisplayName("A Bumped line has nothing left to release: a Release is refused with NOT_ALLOCATED and posts nothing")
    void refusesToReleaseABumpedLine() throws Exception {
        LocalDate d = dayD();
        long[] bumped = submitted(oscar, room, at(d, 10), at(d, 12), roomB, false);
        submitted(alice, room, at(d, 10), at(d, 12), roomB, true);

        mvc.perform(post("/api/requests/{id}/lines/{line}/release", bumped[0], bumped[1]).with(as(oscar))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ALLOCATED"));
        assertThat(refundsOf(bumped[1])).containsExactly("30.00");
    }

    @Test
    @DisplayName("A Bumped line heads its waitlist: freed capacity goes to it before an earlier-submitted standard line")
    void fillsTheBumpedLineFirst() throws Exception {
        LocalDate d = dayD();
        submitted(bob, room, at(d, 10), at(d, 12), roomA, false);
        long[] bumped = submitted(oscar, room, at(d, 10), at(d, 12), roomB, false);
        long[] priority = submitted(alice, room, at(d, 10), at(d, 12), null, true);
        long[] waiting = submitted(bob, room, at(d, 10), at(d, 12), null, false);
        detail(as(oscar), bumped[0]).andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
        detail(as(bob), waiting[0]).andExpect(jsonPath("$.lines[0].waitlistPosition").value(2));

        mvc.perform(post("/api/requests/{id}/lines/{line}/release", priority[0], priority[1]).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());

        detail(as(oscar), bumped[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"));
        assertThat(rowStates(bumped[1])).containsExactly("BUMPED", "ALLOCATED");
        detail(as(bob), waiting[0]).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    @Test
    @DisplayName("A standard line never Bumps: it waits, and the blocking lines stay")
    void aStandardLineNeverBumps() throws Exception {
        LocalDate d = dayD();
        long[] a = submitted(oscar, room, at(d, 10), at(d, 12), roomA, false);
        long[] b = submitted(oscar, room, at(d, 10), at(d, 12), roomB, false);

        long[] standard = submitted(alice, room, at(d, 10), at(d, 12), null, false);

        detail(as(alice), standard[0]).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(rowStates(a[1])).containsExactly("ALLOCATED");
        assertThat(rowStates(b[1])).containsExactly("ALLOCATED");
        assertThat(waitlistEntryOf(standard[1])).containsExactly("false/false");
    }

    @Test
    @DisplayName("A plan gone stale under the lock is refused with INSIDE_BUMP_CUTOFF, and nothing is Bumped")
    void refusesAStalePlan() throws Exception {
        LocalDate d = dayD();
        long[] blocking = submitted(oscar, room, at(d, 10), at(d, 12), roomA, false);
        long draft = createDraft(as(alice));
        long line = line(as(alice), draft, room, 1, at(d, 10), at(d, 12), roomA);
        LineSnapshot snapshot = new LineSnapshot(new RequestLineId(line), new RequestId(draft), new TeamId(platform),
                new ResourceTypeId(room), AllocationClass.EXCLUSIVE, 1, null, new TimeWindow(at(d, 10), at(d, 12)),
                Optional.of(new ResourceId(roomA)), true, true, clock.instant(), false);
        Bumpable bumpable = allocators.capability(AllocationClass.EXCLUSIVE, Bumpable.class).orElseThrow();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            typeLock.lockTypes(new TreeSet<>(List.of(new ResourceTypeId(room))));
            BumpPlan plan = bumpable.planBump(snapshot).orElseThrow();
            assertThat(plan.bumped()).extracting(bumped -> bumped.line().value()).containsExactly(blocking[1]);
            // The blocking line's occurrence is now within 24 hours.
            movableClock.set(at(d, 10).minus(Duration.ofHours(23)));
            bumpable.applyBump(plan);
        })).isInstanceOfSatisfying(RuleViolation.class,
                refused -> assertThat(refused.code()).isEqualTo("INSIDE_BUMP_CUTOFF"));
        assertThat(rowStates(blocking[1])).containsExactly("ALLOCATED");
        assertThat(rowStates(line)).isEmpty();
    }

    @Test
    @DisplayName("#27: a Waiting Request that keeps an Allocated line after a Bump is withdrawn only once that line is Released")
    void refusesToWithdrawAWaitingRequestThatStillHasAnAllocatedLine() throws Exception {
        LocalDate d = dayD();
        // As in exc-bump-effect: O1 (oscar) has Room B (30.00) and GPU 4 units (60.00), both Allocated; Alice's
        // Priority line Bumps the room line, so O1 is Waiting while its GPU line stays Allocated.
        submitted(bob, room, at(d, 10), at(d, 12), roomA, false);
        long o1 = createDraft(as(oscar));
        long roomLine = line(as(oscar), o1, room, 1, at(d, 10), at(d, 12), roomB);
        long gpuLine = line(as(oscar), o1, gpu, 4, at(d, 10), at(d, 12), null);
        nextSecond();
        submit(as(oscar), o1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        submitted(alice, room, at(d, 10), at(d, 12), null, true);
        detail(as(oscar), o1).andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));
        Map<String, Object> before = account(operations);

        // Withdrawing would cancel the GPU line while its units stay allocated and its Charge stands: refused,
        // as reopening is, and nothing changes.
        withdraw(as(oscar), o1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_STATE"))
                .andExpect(jsonPath("$.detail").value(containsString("Release its allocated lines first")));
        detail(as(oscar), o1).andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));
        assertThat(meteredRowStates(gpuLine)).containsExactly("ALLOCATED");
        assertThat(waitlistEntryOf(roomLine)).containsExactly("true/false");
        assertThat(account(operations)).isEqualTo(before);

        // Once the Manager Releases the GPU line, the withdrawal goes through and the room line leaves the waitlist.
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", o1, gpuLine).with(as(oscar))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());
        withdraw(as(oscar), o1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WITHDRAWN"));
        assertThat(meteredRowStates(gpuLine)).doesNotContain("ALLOCATED", "HELD");
        assertThat(waitlistEntryOf(roomLine)).isEmpty();
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    /** The states of the line's Metered allocation rows, by id. */
    private List<String> meteredRowStates(long line) {
        return jdbc.queryForList("SELECT state FROM metered_allocation WHERE line_id = ? ORDER BY id", String.class,
                line);
    }
}
