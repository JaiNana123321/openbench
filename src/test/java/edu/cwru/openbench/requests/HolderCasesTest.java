package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * The holder views of the Inventory (docs/test-cases, Section "Inventory": {@code inv-holder} and
 * {@code inv-filter}; issue #11): a Resource's timeline of free, on Hold, and allocated parts, and
 * the free-for-a-window filter, through the API, with the refusals of both endpoints.
 *
 * <p>Room A and Room B belong to this world's Dave Kim, the Resource Owner of the test world, and the
 * Meeting room type has the attribute {@code wing} (Room A North, Room B South).
 */
class HolderCasesTest extends MatchedWorldTest {

    private String roomBName;

    @BeforeEach
    void giveTheRoomsToDaveAndTheirWings() {
        jdbc.update("UPDATE resource SET owner_member_id = ? WHERE type_id = ?", world.dave().id(), room);
        roomBName = jdbc.queryForObject("SELECT name FROM resource WHERE id = ?", String.class, roomB);
        long wing = testData().attribute(room, "wing", "LIST", "North", "South");
        testData().attributeValue(roomA, wing, "North");
        testData().attributeValue(roomB, wing, "South");
    }

    @Test
    @DisplayName("inv-holder: Room A's timeline shows free, allocated, and on Hold parts with their Team and Request")
    @Verifies("inv-holder")
    void theTimelineShowsEachPartWithItsHolder() throws Exception {
        LocalDate d = dayD();
        // Preconditions: A1 (Alice) has Room A, D 10:00-12:00, Allocated; B1 (Bob) has a Matched line
        // (Java, 1, D to D+1) and Room A, D 14:00-16:00, so it is Pending and Room A is on Hold for it.
        long a1 = allocatedA1(d);
        long b1 = createDraft(as(bob));
        personLine(as(bob), b1, 1, d, d.plusDays(1), java);
        line(as(bob), b1, room, 1, at(d, 14), at(d, 16), roomA);
        submit(as(bob), b1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));

        // 1. Room A, D 08:00-18:00, as Dave, its owner: every part, with the Team and Request of each holder.
        timeline(dave, roomA, at(d, 8), at(d, 18))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(part(0, d, 8, 10, "FREE"))
                .andExpect(part(1, d, 10, 12, "ALLOCATED"))
                .andExpect(part(2, d, 12, 14, "FREE"))
                .andExpect(part(3, d, 14, 16, "HELD"))
                .andExpect(part(4, d, 16, 18, "FREE"))
                .andExpect(jsonPath("$[0].team").value(nullValue()))
                .andExpect(jsonPath("$[0].request").value(nullValue()))
                .andExpect(jsonPath("$[1].team.id").value(platform))
                .andExpect(jsonPath("$[1].team.name").value(teamName(platform)))
                .andExpect(jsonPath("$[1].request").value(a1))
                .andExpect(jsonPath("$[1].priority").value(false))
                .andExpect(jsonPath("$[3].team.id").value(research))
                .andExpect(jsonPath("$[3].team.name").value(teamName(research)))
                .andExpect(jsonPath("$[3].request").value(b1));

        // 1, as Ben, a Member: the same parts; the holding Team and Request are hidden from him (design
        // Table "What each role sees": "Inventory open, holding Team and Request hidden").
        timeline(ben, roomA, at(d, 8), at(d, 18))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].state", contains("FREE", "ALLOCATED", "FREE", "HELD", "FREE")))
                .andExpect(jsonPath("$[1].team").value(nullValue()))
                .andExpect(jsonPath("$[1].request").value(nullValue()))
                .andExpect(jsonPath("$[3].team").value(nullValue()))
                .andExpect(jsonPath("$[3].request").value(nullValue()));

        // A Manager sees their own Team's holder only; a Capacity Planner sees every holder.
        timeline(alice, roomA, at(d, 8), at(d, 18))
                .andExpect(jsonPath("$[1].request").value(a1))
                .andExpect(jsonPath("$[3].state").value("HELD"))
                .andExpect(jsonPath("$[3].request").value(nullValue()));
        timeline(erin, roomA, at(d, 8), at(d, 18))
                .andExpect(jsonPath("$[1].request").value(a1))
                .andExpect(jsonPath("$[3].request").value(b1));

        // 2. Dave, the Resource Owner, has no approval request and no notice for A1 or B1, and no Request screen.
        assertThat(notificationsOf(world.dave())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval WHERE approver_member_id = ?", Integer.class,
                world.dave().id())).isZero();
        mvc.perform(get("/api/notifications").with(as(dave))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
        mvc.perform(get("/api/approvals").with(as(dave))).andExpect(refused(403, "FORBIDDEN"));
        detail(as(dave), a1).andExpect(refused(403, "FORBIDDEN"));
        detail(as(dave), b1).andExpect(refused(403, "FORBIDDEN"));
    }

    @Test
    @DisplayName("Inventory.View.Holder: a Released occurrence shows as free, and a Priority line carries its mark")
    void releasedPartsAreFreeAndPriorityIsMarked() throws Exception {
        LocalDate d = dayD();
        long a1 = createDraft(as(alice));
        long l1 = line(as(alice), a1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), a1).andExpect(jsonPath("$.state").value("ALLOCATED"));
        long a2 = createDraft(as(alice));
        long l2 = line(as(alice), a2, room, 1, at(d, 13), at(d, 15), roomA);
        mvc.perform(put("/api/requests/{id}/lines/{line}/priority", a2, l2).with(as(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\": true}")).andExpect(status().isOk());
        submit(as(alice), a2).andExpect(jsonPath("$.state").value("ALLOCATED"));

        mvc.perform(post("/api/requests/{id}/lines/{line}/release", a1, l1).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());

        timeline(alice, roomA, at(d, 8), at(d, 18))
                .andExpect(jsonPath("$[*].state", contains("FREE", "ALLOCATED", "FREE")))
                .andExpect(part(0, d, 8, 13, "FREE"))
                .andExpect(part(1, d, 13, 15, "ALLOCATED"))
                .andExpect(jsonPath("$[1].request").value(a2))
                .andExpect(jsonPath("$[1].priority").value(true));
        // Hidden from a Member: the mark goes with the holder.
        timeline(ben, roomA, at(d, 8), at(d, 18)).andExpect(jsonPath("$[1].priority").value(false));
    }

    @Test
    @DisplayName("inv-filter: Resources by type, by attribute value, and free for a window; back-to-back windows do not overlap")
    @Verifies("inv-filter")
    void filtersByTypeAttributeAndFreeWindow() throws Exception {
        LocalDate d = dayD();
        // Precondition: A1 (Alice) has Room A, D 10:00-12:00, Allocated.
        allocatedA1(d);

        // 1. Meeting room: Room A and Room B only.
        mvc.perform(get("/api/inventory/resources").param("type", Long.toString(room)).with(as(ben)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains(roomAName, roomBName)));
        // 2. wing = South: Room B only.
        mvc.perform(get("/api/inventory/resources").param("type", Long.toString(room)).param("attr.wing", "South")
                        .with(as(ben)))
                .andExpect(jsonPath("$.content[*].name", contains(roomBName)));
        // 3. Free D 11:00-13:00: Room B only; Room A is busy for part of the window.
        free(ben, room, at(d, 11), at(d, 13))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains(roomBName)))
                .andExpect(jsonPath("$.totalElements").value(1));
        // 4. Free D 12:00-14:00: Room A and Room B; back-to-back windows do not overlap.
        free(ben, room, at(d, 12), at(d, 14))
                .andExpect(jsonPath("$.content[*].name", contains(roomAName, roomBName)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("Inventory.View.Filter: the free filter takes attribute values and pages, leaves out retired Resources, and counts Holds")
    void theFreeFilterCombinesWithAttributesAndPages() throws Exception {
        LocalDate d = dayD();
        // A Hold blocks like an allocation: Room A on Hold for B1, D 10:00-12:00.
        long b1 = createDraft(as(bob));
        personLine(as(bob), b1, 1, d, d.plusDays(1), java);
        line(as(bob), b1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(bob), b1).andExpect(jsonPath("$.state").value("PENDING"));
        free(ben, room, at(d, 9), at(d, 11)).andExpect(jsonPath("$.content[*].name", contains(roomBName)));
        // Free from the window's start, the end of the Hold.
        free(ben, room, at(d, 12), at(d, 13)).andExpect(jsonPath("$.content[*].name", contains(roomAName, roomBName)));

        mvc.perform(get("/api/inventory/resources/free").with(as(ben)).param("type", Long.toString(room))
                        .param("from", at(d, 12).toString()).param("to", at(d, 13).toString())
                        .param("attr.wing", "North"))
                .andExpect(jsonPath("$.content[*].name", contains(roomAName)));
        mvc.perform(get("/api/inventory/resources/free").with(as(ben)).param("type", Long.toString(room))
                        .param("from", at(d, 12).toString()).param("to", at(d, 13).toString())
                        .param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.content[*].name", contains(roomBName)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));

        jdbc.update("UPDATE resource SET retired_at = now() WHERE id = ?", roomB);
        free(ben, room, at(d, 12), at(d, 13)).andExpect(jsonPath("$.content[*].name", contains(roomAName)));
    }

    @Test
    @DisplayName("Inventory.View.Holder: a window may be given with any offset and may be up to 31 days long")
    void acceptsAnyOffsetAndAMonthLongWindow() throws Exception {
        LocalDate d = dayD();
        allocatedA1(d);
        mvc.perform(get("/api/inventory/resources/{id}/holders", roomA).with(as(dave))
                        .param("from", offsetTime(at(d, 8)))
                        .param("to", offsetTime(at(d, 18))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].state", contains("FREE", "ALLOCATED", "FREE")));
        timeline(dave, roomA, at(d, 0), at(d, 0).plusSeconds(31 * 24 * 3600L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].state", contains("FREE", "ALLOCATED", "FREE")));
    }

    @Test
    @DisplayName("Inventory.View.Holder: refuses an unknown Resource, a bad window, and a Resource that is not Exclusive")
    void refusesBadTimelineRequests() throws Exception {
        LocalDate d = dayD();
        timeline(ben, Long.MAX_VALUE, at(d, 8), at(d, 18)).andExpect(refused(404, "NOT_FOUND"));
        timeline(ben, roomA, at(d, 18), at(d, 8)).andExpect(refused(422, "WINDOW_INVALID"));
        timeline(ben, roomA, at(d, 8), at(d, 8)).andExpect(refused(422, "WINDOW_INVALID"));
        timeline(ben, roomA, at(d, 8).plusSeconds(1800), at(d, 18)).andExpect(refused(422, "WINDOW_INVALID"));
        timeline(ben, roomA, at(d, 0), at(d, 0).plusSeconds(31 * 24 * 3600L + 3600))
                .andExpect(refused(422, "WINDOW_INVALID"));
        long node = jdbc.queryForObject("SELECT id FROM resource WHERE type_id = ? ORDER BY id LIMIT 1", Long.class, gpu);
        timeline(ben, node, at(d, 8), at(d, 18)).andExpect(refused(422, "CLASS_MISMATCH"));
        mvc.perform(get("/api/inventory/resources/{id}/holders", roomA).with(as(ben)).param("from", at(d, 8).toString()))
                .andExpect(refused(400, "VALIDATION_FAILED"));
        mvc.perform(get("/api/inventory/resources/{id}/holders", roomA).with(as(ben))
                        .param("from", "tomorrow").param("to", at(d, 18).toString()))
                .andExpect(refused(400, "VALIDATION_FAILED"));
        mvc.perform(get("/api/inventory/resources/{id}/holders", roomA)
                        .param("from", at(d, 8).toString()).param("to", at(d, 18).toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Inventory.View.Filter: the free filter refuses an unknown type, a bad window, and a type that is not Exclusive")
    void refusesBadFreeSearches() throws Exception {
        LocalDate d = dayD();
        free(ben, Long.MAX_VALUE, at(d, 8), at(d, 18)).andExpect(refused(404, "NOT_FOUND"));
        free(ben, room, at(d, 18), at(d, 8)).andExpect(refused(422, "WINDOW_INVALID"));
        free(ben, room, at(d, 8), at(d, 9).plusSeconds(60)).andExpect(refused(422, "WINDOW_INVALID"));
        free(ben, gpu, at(d, 8), at(d, 18)).andExpect(refused(422, "CLASS_MISMATCH"));
        free(ben, person, at(d, 8), at(d, 18)).andExpect(refused(422, "CLASS_MISMATCH"));
        mvc.perform(get("/api/inventory/resources/free").with(as(ben))
                        .param("from", at(d, 8).toString()).param("to", at(d, 18).toString()))
                .andExpect(refused(400, "VALIDATION_FAILED"));
        mvc.perform(get("/api/inventory/resources/free").with(as(ben)).param("type", Long.toString(room))
                        .param("from", at(d, 8).toString()).param("to", at(d, 18).toString()).param("size", "500"))
                .andExpect(refused(400, "VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- helpers

    /** A1: Alice's Request with Room A, D 10:00-12:00, submitted and Allocated; returns its id. */
    private long allocatedA1(LocalDate d) throws Exception {
        long a1 = createDraft(as(alice));
        line(as(alice), a1, room, 1, at(d, 10), at(d, 12), roomA);
        submit(as(alice), a1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));
        return a1;
    }

    private ResultActions timeline(String username, long resource, Instant from, Instant to) throws Exception {
        return mvc.perform(get("/api/inventory/resources/{id}/holders", resource).with(as(username))
                .param("from", from.toString()).param("to", to.toString()));
    }

    private ResultActions free(String username, long type, Instant from, Instant to) throws Exception {
        return mvc.perform(get("/api/inventory/resources/free").with(as(username)).param("type", Long.toString(type))
                .param("from", from.toString()).param("to", to.toString()));
    }

    /** Part {@code index} runs on day {@code d} from {@code from} to {@code to} o'clock in this state. */
    private ResultMatcher part(int index, LocalDate d, int from, int to, String state) {
        return result -> {
            jsonPath("$[" + index + "].state").value(state).match(result);
            jsonPath("$[" + index + "].window.start").value(offsetTime(at(d, from))).match(result);
            jsonPath("$[" + index + "].window.end").value(offsetTime(at(d, to))).match(result);
        };
    }

    /** The instant as the API writes it: with the company zone's offset, such as 2026-12-02T08:00:00-05:00. */
    private String offsetTime(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(clock.getZone()));
    }

    private String teamName(long team) {
        return jdbc.queryForObject("SELECT name FROM team WHERE id = ?", String.class, team);
    }

    private static ResultMatcher refused(int status, String code) {
        return result -> {
            status().is(status).match(result);
            jsonPath("$.code").value(code).match(result);
        };
    }
}
