package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Functional test cases of the Release area (docs/test-cases, Section 4.9) and the waitlist fill
 * (exc-waitlist-fill, met-waitlist-fill, lif-reevaluate) through the API, on the demo world and the
 * days of the document's Section 2. The timelines of the cases (#11) are checked through the
 * allocation rows; the notifications (#3) are not checked yet.
 */
class ReleaseCasesTest extends ReleaseTest {

    @Test
    @DisplayName("rel-whole: a whole line released seven or more days ahead returns 75%, 22.50 of 30.00, and frees Room A")
    @Verifies("rel-whole")
    void releasesAWholeLine() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        assertThat(balance(platform)).isEqualByComparingTo("4970.00");

        // 1. Release L1 in whole: Released, recorded with the part, lead time, fraction, Refund, actor, and time.
        release(alice, a1[0], a1[1], "{\"kind\": \"WHOLE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line.id").value(a1[1]))
                .andExpect(jsonPath("$.line.state").value("RELEASED"))
                .andExpect(jsonPath("$.refund.attributable").value(30.00))
                .andExpect(jsonPath("$.refund.refund").value(22.50))
                .andExpect(jsonPath("$.refund.pieces[0].fraction").value(0.75))
                .andExpect(jsonPath("$.refund.pieces[0].released.start").value(json(at(d, 10))))
                .andExpect(jsonPath("$.refund.pieces[0].leadHours").value(greaterThanOrEqualTo(168)));
        detail(as(alice), a1[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("RELEASED"))
                .andExpect(jsonPath("$.lines[0].allocatedParts.length()").value(0))
                .andExpect(jsonPath("$.history[-1].line").value(a1[1]))
                .andExpect(jsonPath("$.history[-1].fromState").value("ALLOCATED"))
                .andExpect(jsonPath("$.history[-1].toState").value("RELEASED"))
                .andExpect(jsonPath("$.history[-1].actorName").value(alice))
                .andExpect(jsonPath("$.history[-1].at").isNotEmpty())
                .andExpect(jsonPath("$.history[-1].note").value(allOf(containsString("the whole line"),
                        containsString("lead time"), containsString("75%"), containsString("Refund 22.50 credits"))));

        // 2. The Statement: one Refund of 22.50 against L1's Charge; Balance 4992.50.
        statement(as(alice), platform)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'REFUND')].line").value((int) a1[1]))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].line").value((int) a1[1]))
                .andExpect(jsonPath("$.totals.refunds").value(22.50))
                .andExpect(jsonPath("$.account.balance").value(4992.50));
        assertThat(refundsOf(a1[1])).containsExactly("22.50/22.50/0.00");

        // 3. Room A's timeline for D: 10:00-12:00 is free.
        assertThat(liveOn(roomA, at(d, 10), at(d, 12))).isEmpty();

        // 4. As bob, Release L1: NOT_FOUND, only the owning Team's Manager may Release.
        releaseWhole(bob, a1[0], a1[1])
                .andExpect(status().isNotFound());
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
    }

    @Test
    @DisplayName("rel-partial, steps 1, 2, and 4 (GPU): an earlier end returns 45.00 and 2 fewer units 22.50; L1 keeps its window, units, and prices")
    @Verifies(value = "rel-partial", partial = true)
    void releasesPartsOfAMeteredLine() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, gpu, 4, at(d, 10), at(d, 14), null);
        detail(as(alice), a1[0]).andExpect(jsonPath("$.lines[0].line.quote").value(120.00));

        // 1. Release L1's hours 12:00-14:00: 4 units kept for 10:00-12:00; Refund 45.00 (60.00 x 0.75).
        release(alice, a1[0], a1[1], "{\"kind\": \"EARLIER_END\", \"newEnd\": \"" + at(d, 12) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line.state").value("PARTLY_RELEASED"))
                .andExpect(jsonPath("$.refund.attributable").value(60.00))
                .andExpect(jsonPath("$.refund.refund").value(45.00));
        detail(as(alice), a1[0])
                .andExpect(jsonPath("$.lines[0].line.quote").value(120.00))
                .andExpect(jsonPath("$.lines[0].allocatedParts.length()").value(1))
                .andExpect(jsonPath("$.lines[0].allocatedParts[0].window.start").value(json(at(d, 10))))
                .andExpect(jsonPath("$.lines[0].allocatedParts[0].window.end").value(json(at(d, 12))))
                .andExpect(jsonPath("$.lines[0].allocatedParts[0].amount").value(4));

        // 2. Release 2 of L1's units: 2 units kept for 10:00-12:00; Refund 22.50 (30.00 x 0.75).
        release(alice, a1[0], a1[1], "{\"kind\": \"FEWER_UNITS\", \"newUnits\": 2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line.state").value("PARTLY_RELEASED"))
                .andExpect(jsonPath("$.refund.attributable").value(30.00))
                .andExpect(jsonPath("$.refund.refund").value(22.50));

        // 4. The GPU timeline: 2 units for Platform on D 10:00-12:00, and nothing else of L1 live.
        assertThat(jdbc.queryForList("""
                SELECT units || ' ' || state || ' ' || to_char(starts_at AT TIME ZONE 'UTC', 'HH24') || '-'
                       || to_char(ends_at AT TIME ZONE 'UTC', 'HH24') || ' team ' || team_id
                FROM metered_allocation WHERE line_id = ? AND state = 'ALLOCATED'""", String.class, a1[1]))
                .containsExactly("2 ALLOCATED " + utcHour(at(d, 10)) + "-" + utcHour(at(d, 12)) + " team " + platform);
        assertThat(refundsOf(a1[1])).containsExactly("45.00/45.00/0.00", "22.50/22.50/0.00");
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
    }

    @Test
    @DisplayName("rel-schedule, step 1: a whole line released under seven days ahead returns 25%, 7.50 of 30.00")
    @Verifies(value = "rel-schedule", partial = true)
    void returnsAQuarterUnderSevenDays() throws Exception {
        LocalDate e = dayE();
        long[] l1 = submitted(alice, room, 1, at(e, 10), at(e, 12), roomA);

        releaseWhole(alice, l1[0], l1[1])
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.pieces[0].fraction").value(0.25))
                .andExpect(jsonPath("$.refund.refund").value(7.50));

        assertThat(refundsOf(l1[1])).containsExactly("7.50/7.50/0.00");
        statement(as(alice), platform).andExpect(jsonPath("$.totals.refunds").value(7.50));
    }

    @Test
    @DisplayName("rel-bucket: a Refund of 360.00 returns 90.00 granted and 270.00 earned, the Charge's proportions")
    @Verifies("rel-bucket")
    void refundsInTheChargesProportions() throws Exception {
        LocalDate d = dayD();
        testData().postEarning(research, "800.00");
        long b1 = createDraft(as(bob));
        long l1 = line(as(bob), b1, microscope, 1, at(d.plusDays(7), 9), at(d.plusDays(7), 17), null);
        long l2 = line(as(bob), b1, microscope, 1, at(d.plusDays(8), 9), at(d.plusDays(8), 17), null);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("ALLOCATED"));

        // 1. The Charges: L1 480.00 granted; L2 120.00 granted and 360.00 earned. Granted 0.00, earned 440.00.
        assertThat(jdbc.queryForList("""
                SELECT line_id || ':' || granted_part || '/' || earned_part FROM transaction
                WHERE kind = 'CHARGE' AND team_id = ? ORDER BY id""", String.class, research))
                .containsExactly(l1 + ":480.00/0.00", l2 + ":120.00/360.00");
        statement(as(bob), research)
                .andExpect(jsonPath("$.account.granted").value(0.00))
                .andExpect(jsonPath("$.account.earned").value(440.00));

        // 2. Release L2 in whole: one Refund of 360.00 (480.00 x 0.75), 90.00 granted and 270.00 earned.
        releaseWhole(bob, b1, l2).andExpect(status().isOk()).andExpect(jsonPath("$.refund.refund").value(360.00));
        assertThat(refundsOf(l2)).containsExactly("360.00/90.00/270.00");

        // 3. The Account: granted 90.00, earned 710.00, Balance 800.00.
        mvc.perform(get("/api/ledger/account")
                        .with(as(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(90.00))
                .andExpect(jsonPath("$.earned").value(710.00))
                .andExpect(jsonPath("$.balance").value(800.00));
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    @Test
    @DisplayName("exc-waitlist-fill: the freed slot goes to the first waiting line whose window fits, and its Manager is notified")
    @Verifies("exc-waitlist-fill")
    void givesTheFreedSlotToTheFirstLineThatFits() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long[] o1 = submitted(oscar, room, 1, at(d, 12), at(d, 14), roomA);
        long[] b1 = submitted(bob, room, 1, at(d, 10), at(d, 13), roomA);
        long[] o2 = submitted(oscar, room, 1, at(d, 10), at(d, 11), roomA);
        assertThat(requestState(bob, b1[0])).isEqualTo("WAITING");
        assertThat(requestState(oscar, o2[0])).isEqualTo("WAITING");
        BigDecimal operationsBefore = available(operations);

        // 1. Release A1's line in whole: Released, with its Refund.
        releaseWhole(alice, a1[0], a1[1]).andExpect(status().isOk()).andExpect(jsonPath("$.refund.refund").value(22.50));
        assertThat(noticesOf(alice, a1[0])).last().asString()
                .isEqualTo("REQUEST_UPDATE: Line " + a1[1] + " of Request " + a1[0] + " was Released; Refund 22.50 credits.");

        // 2. B1 still Waiting at position 1 (12:00-13:00 is O1's); O2 Allocated on Room A for 10:00-11:00.
        detail(as(bob), b1[0])
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
        detail(as(oscar), o2[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].id").value(roomA))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty())
                .andExpect(jsonPath("$.history[-1].fromState").value("WAITING"))
                .andExpect(jsonPath("$.history[-1].toState").value("ALLOCATED"))
                .andExpect(jsonPath("$.history[-1].actor").isEmpty());
        assertThat(liveOn(roomA, at(d, 10), at(d, 14))).containsExactly(o2[1] + ":ALLOCATED", o1[1] + ":ALLOCATED");
        // Oscar is notified that O2 was satisfied from the waitlist; Bob is not, for B1.
        assertThat(noticesOf(oscar, o2[0])).last().asString().startsWith("WAITLIST_FILLED: ");
        assertThat(noticesOf(bob, b1[0])).noneMatch(notice -> notice.startsWith("WAITLIST_FILLED"));
        // O2's Charge is its locked Quote, 15.00, posted by the system.
        assertThat(jdbc.queryForList("SELECT amount || ' ' || coalesce(actor_id::text, 'system') FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                String.class, o2[1])).containsExactly("15.00 system");
        assertThat(available(operations)).isEqualByComparingTo(operationsBefore.subtract(new BigDecimal("15.00")));
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }

    @Test
    @DisplayName("met-waitlist-fill: B2 is allocated when its 4 units fit in every hour, at its locked Quote, and Bob is notified")
    @Verifies("met-waitlist-fill")
    void allocatesAWaitingMeteredLine() throws Exception {
        LocalDate d = dayD();
        // The state after met-quota: alice holds 2 units; B1 and B3 Allocated, B2 Waiting at position 1.
        submitted(alice, gpu, 2, at(d, 10), at(d, 12), null);
        long[] b1 = submitted(bob, gpu, 6, at(d, 10), at(d, 12), null);
        long[] b2 = submitted(bob, gpu, 4, at(d, 11), at(d, 13), null);
        long[] b3 = submitted(bob, gpu, 2, at(d, 11), at(d, 13), null);
        detail(as(bob), b2[0])
                .andExpect(jsonPath("$.lines[0].reason").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1));
        assertThat(requestState(bob, b3[0])).isEqualTo("ALLOCATED");

        // 1. Release B1's line in whole.
        releaseWhole(bob, b1[0], b1[1]).andExpect(status().isOk()).andExpect(jsonPath("$.refund.refund").value(67.50));

        // 2. B2 Allocated: Research holds 2 + 4 = 6 units at 11:00 and 12:00, within its Quota.
        detail(as(bob), b2[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(60.00));
        assertThat(noticesOf(bob, b2[0])).last().asString().startsWith("WAITLIST_FILLED: ");
        assertThat(jdbc.queryForObject("""
                SELECT sum(units) FROM metered_allocation
                WHERE team_id = ? AND type_id = ? AND state = 'ALLOCATED' AND starts_at <= ? AND ends_at > ?""",
                Integer.class, research, gpu, utc(at(d, 11)), utc(at(d, 11)))).isEqualTo(6);
        // Pass criteria: B2's Charge equals the Quote locked when B2 was submitted.
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, b2[1])).isEqualByComparingTo("60.00");
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    @Test
    @DisplayName("lif-reevaluate: a Waiting Request is allocated only when every Required line fits at once, never in part")
    @Verifies("lif-reevaluate")
    void allocatesAWaitingRequestOnlyWhole() throws Exception {
        LocalDate d = dayD();
        long[] a1 = submitted(alice, room, 1, at(d, 10), at(d, 12), roomA);
        long[] o1 = submitted(oscar, gpu, 16, at(d, 10), at(d, 12), null);
        long b1 = createDraft(as(bob));
        long b1Room = line(as(bob), b1, room, 1, at(d, 10), at(d, 12), roomA);
        long b1Gpu = line(as(bob), b1, gpu, 6, at(d, 10), at(d, 12), null);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("WAITING"));
        long[] o2 = submitted(oscar, room, 1, at(d, 10), at(d, 12), roomA);
        detail(as(oscar), o2[0]).andExpect(jsonPath("$.lines[0].waitlistPosition").value(2));

        // 1. As alice, Release A1's line: Room A frees; B1 cannot have L2, so the room passes to O2.
        releaseWhole(alice, a1[0], a1[1]).andExpect(status().isOk());
        assertThat(requestState(oscar, o2[0])).isEqualTo("ALLOCATED");

        // 2. B1 Waiting; L1 still at position 1 for Room A; L2 still waiting.
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1))
                .andExpect(jsonPath("$.lines[1].line.state").value("WAITING"));
        assertNothingLive(b1Room, b1Gpu);

        // 3. As oscar, Release O1's line: L2 would fit, but Room A is O2's, so B1 waits.
        releaseWhole(oscar, o1[0], o1[1]).andExpect(status().isOk());
        assertThat(requestState(bob, b1)).isEqualTo("WAITING");
        assertNothingLive(b1Room, b1Gpu);

        // ... then O2's line: both fit, and B1 is Allocated at its locked Quotes (30.00 and 90.00).
        BigDecimal researchBefore = available(research);
        releaseWhole(oscar, o2[0], o2[1]).andExpect(status().isOk());
        detail(as(bob), b1)
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty())
                .andExpect(jsonPath("$.lines[1].waitlistPosition").isEmpty());
        assertThat(jdbc.queryForList("SELECT line_id || ':' || amount FROM transaction WHERE kind = 'CHARGE' AND line_id IN (?, ?) ORDER BY line_id",
                String.class, b1Room, b1Gpu)).containsExactly(b1Room + ":30.00", b1Gpu + ":90.00");
        assertThat(available(research)).isEqualByComparingTo(researchBefore.subtract(new BigDecimal("120.00")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id IN (?, ?)", Integer.class,
                b1Room, b1Gpu)).isZero();
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    private void assertNothingLive(long... lines) {
        for (long line : lines) {
            assertThat(jdbc.queryForObject("""
                    SELECT (SELECT count(*) FROM exclusive_occurrence WHERE line_id = :line AND state IN ('HELD', 'ALLOCATED'))
                         + (SELECT count(*) FROM metered_allocation WHERE line_id = :line AND state IN ('HELD', 'ALLOCATED'))
                         + (SELECT count(*) FROM transaction WHERE line_id = :line)
                         + (SELECT count(*) FROM held_credit WHERE line_id = :line)""".replace(":line", Long.toString(line)),
                    Integer.class)).as("rows and credits of line %d", line).isZero();
        }
    }


    /** The notices sent to the Member about the Request, oldest first, as "KIND: text". */
    private List<String> noticesOf(String username, long requestId) {
        return jdbc.queryForList("""
                SELECT n.kind || ': ' || n.text FROM notification n JOIN member m ON m.id = n.member_id
                WHERE m.username = ? AND n.link = ? ORDER BY n.id""", String.class, username, "/requests/" + requestId);
    }

    private static String utcHour(Instant instant) {
        return String.format("%02d", instant.atZone(ZoneOffset.UTC).getHour());
    }
}
