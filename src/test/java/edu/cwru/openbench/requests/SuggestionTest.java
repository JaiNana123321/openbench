package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.ledger.LedgerService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SuggestionTest extends PostgresTest {
    @Autowired Clock clock;
    @Autowired LedgerService ledger;
    private long team;
    private long period;
    private long member;
    private long manager;
    private long room;
    private long gpu;
    private long roomA;
    private long roomB;
    private String ben;
    private String alice;
    private String bob;
    private Instant start;
    private Instant end;

    @BeforeEach
    void world() {
        team = testData().team(unique("Platform"));
        alice = unique("alice");
        manager = testData().member(team, alice, Role.MANAGER);
        ben = unique("ben");
        member = testData().member(team, ben);
        long other = testData().team(unique("Research"));
        bob = unique("bob");
        testData().member(other, bob, Role.MANAGER, Role.PLANNER);
        long owner = testData().member(other, unique("dave"), Role.RESOURCE_OWNER);
        room = testData().resourceType(unique("Meeting room"), "EXCLUSIVE");
        gpu = testData().resourceType(unique("GPU"), "METERED");
        roomA = testData().resource(room, unique("Room A"), owner, 1);
        roomB = testData().resource(room, unique("Room B"), owner, 1);
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 10)", room);
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 5)", gpu);
        jdbc.update("""
                INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier)
                VALUES (?, '12345', '09:00', '17:00', 1.5)
                """, room);
        period = testData().periodContaining(LocalDate.now(clock));
        ledger.postGrant(new TeamId(team), new PeriodId(period), Credits.of("5000.00"), "Test Grant", null);
        LocalDate d = LocalDate.now(clock).plusDays(14).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
        start = d.atTime(10, 0).atZone(clock.getZone()).toInstant();
        end = d.atTime(12, 0).atZone(clock.getZone()).toInstant();
    }

    @Test
    @Verifies("sug-create")
    void createsOneAskToOwnManagerWithoutHoldsQuotesOrTransactions() throws Exception {
        long before = transactions();
        long s = create(room, 1, "Design review");
        mvc.perform(get("/api/suggestions/{id}", s).with(signedInAs(ben)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.managerId").value(manager))
                .andExpect(jsonPath("$.note").value("Design review"));
        for (long resource : new long[] {roomA, roomB}) {
            mvc.perform(get("/api/inventory/resources/{id}/holders", resource)
                            .param("from", start.toString()).param("to", end.toString()).with(signedInAs(ben)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].state").value("FREE"));
        }
        mvc.perform(get("/api/suggestions/team").with(signedInAs(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(s))
                .andExpect(jsonPath("$.content[0].memberId").value(member));
        mvc.perform(get("/api/ledger/account").with(signedInAs(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(5000.00));
        mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(signedInAs(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.available").value(5000.00))
                .andExpect(jsonPath("$.transactions.length()").value(before));
        assertThat(transactions()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request WHERE team_id = ?", Long.class, team)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_credit WHERE team_id = ?", Long.class, team)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE member_id = ? AND kind = 'SUGGESTION'",
                Long.class, manager)).isEqualTo(1);
        for (String lines : new String[] {"[]", "[" + line(room, 1) + "," + line(room, 1) + "]"}) {
            write(ben, "/api/suggestions", "{\"lines\":" + lines + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @Verifies("sug-accept")
    void acceptsNewAndExistingLinesKeepsOriginalAsksAndQuotesNormally() throws Exception {
        long s1 = create(room, 1, "Design review");
        long s2 = create(room, 1, "Second room");
        long s3 = create(gpu, 2, null);
        long draft = draft(alice);
        long line = number(accept(alice, s1, "{\"requestId\":" + draft + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED")), "$.acceptedLineId");
        mvc.perform(get("/api/requests/{id}/draft", draft).with(signedInAs(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quote").value(30.00))
                .andExpect(jsonPath("$.lines[0].quantity").value(1))
                .andExpect(jsonPath("$.lines[0].window.start").value(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start.atZone(clock.getZone()))));
        accept(alice, s2, "{\"lineId\":" + line + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedLineId").value(line));
        mvc.perform(get("/api/suggestions/request/{id}", draft).with(signedInAs(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        accept(alice, s3, "{\"lineId\":" + line + "}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
        mvc.perform(put("/api/requests/{id}/lines/{line}", draft, line).with(signedInAs(alice)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(line(room, 2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(2));
        for (long s : new long[] {s1, s2}) {
            mvc.perform(get("/api/suggestions/{id}", s).with(signedInAs(ben)))
                    .andExpect(jsonPath("$.proposal.quantity").value(1))
                    .andExpect(jsonPath("$.acceptedLineId").value(line));
        }
        accept(alice, s1, "{\"requestId\":" + draft + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUGGESTION_CLOSED"));
        write(alice, "/api/suggestions/" + s1 + "/decline", "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUGGESTION_CLOSED"));
        mvc.perform(get("/api/suggestions/mine").with(signedInAs(ben)))
                .andExpect(jsonPath("$.content[0].state").value("OPEN"))
                .andExpect(jsonPath("$.content[1].state").value("ACCEPTED"))
                .andExpect(jsonPath("$.content[2].state").value("ACCEPTED"));
        mvc.perform(delete("/api/requests/{id}/lines/{line}", draft, line).with(signedInAs(alice)).with(xsrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_STATE"));
        assertThat(jdbc.queryForObject("SELECT locked_quote FROM request_line WHERE id = ?", Object.class, line)).isNull();
    }

    @Test
    @Verifies("sug-decline")
    void declinesWithAuditNotifiesTheMemberAndNeverDeletes() throws Exception {
        long s = create(gpu, 2, null);
        write(alice, "/api/suggestions/" + s + "/decline", "{\"reason\":\"Use the shared node\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DECLINED"))
                .andExpect(jsonPath("$.decidedBy").value(manager)).andExpect(jsonPath("$.decidedByName").value(alice))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
        mvc.perform(get("/api/notifications").with(signedInAs(ben)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].text").value(
                        org.hamcrest.Matchers.containsString("was declined: Use the shared node")));
        accept(alice, s, "{\"requestId\":" + draft(alice) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUGGESTION_CLOSED"));
        write(alice, "/api/suggestions/" + s + "/decline", "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUGGESTION_CLOSED"));
        mvc.perform(delete("/api/suggestions/{id}", s).with(signedInAs(alice)).with(xsrf()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/suggestions/mine").with(signedInAs(ben)))
                .andExpect(jsonPath("$.content[0].state").value("DECLINED"))
                .andExpect(jsonPath("$.content[0].reason").value("Use the shared node"))
                .andExpect(jsonPath("$.content[0].proposal.quantity").value(2));
    }

    @Test
    void refusesOtherTeamsRolesNonDraftTargetsAndMissingManager() throws Exception {
        long s = create(room, 1, null);
        long draft = draft(alice);
        long otherDraft = draft(bob);
        for (String path : new String[] {"/accept", "/decline"}) {
            write(ben, "/api/suggestions/" + s + path, "{\"requestId\":" + draft + "}")
                    .andExpect(status().isForbidden());
            write(bob, "/api/suggestions/" + s + path, "{\"requestId\":" + draft + "}")
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/suggestions/{id}", s).with(signedInAs(bob))).andExpect(status().isNotFound());
        accept(alice, s, "{\"requestId\":" + otherDraft + "}").andExpect(status().isNotFound());
        long otherLine = testData().requestLine(jdbc.queryForObject("SELECT team_id FROM request WHERE id = ?", Long.class, otherDraft));
        accept(alice, s, "{\"lineId\":" + otherLine + "}").andExpect(status().isNotFound());
        long line = number(write(alice, "/api/requests/" + draft + "/lines", line(room, 1)), "$.id");
        jdbc.update("UPDATE request SET state = 'WAITING' WHERE id = ?", draft);
        for (String target : new String[] {"{\"requestId\":" + draft + "}", "{\"lineId\":" + line + "}"}) {
            accept(alice, s, target).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_STATE"));
        }
        accept(alice, s, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        accept(alice, s, "{\"lineId\":" + line + ",\"requestId\":" + draft + "}").andExpect(status().isBadRequest());
        jdbc.update("UPDATE team SET manager_member_id = NULL WHERE id = ?", team);
        write(ben, "/api/suggestions", "{\"lines\":[" + line(room, 1) + "]}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TEAM_NEEDS_MANAGER"));
    }

    @Test
    void validatesProposalsLikeDraftsAndKeepsTeamListOpenFirstAndPaged() throws Exception {
        for (String proposal : new String[] {line(room, 0), line(room, 1).replace(start.toString(), end.toString()),
                line(999999, 1), line(gpu, 1).replace("}", ",\"namedResourceId\":" + roomA + "}")}) {
            write(ben, "/api/suggestions", "{\"lines\":[" + proposal + "]}").andExpect(status().isUnprocessableEntity());
        }
        long open = create(room, 1, null);
        long closed = create(gpu, 2, null);
        write(alice, "/api/suggestions/" + closed + "/decline", "{}").andExpect(status().isOk());
        mvc.perform(get("/api/suggestions/team").param("size", "1").with(signedInAs(alice)))
                .andExpect(jsonPath("$.content[0].id").value(open)).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/suggestions/team").param("size", "1").param("page", "1").with(signedInAs(alice)))
                .andExpect(jsonPath("$.content[0].id").value(closed));
        mvc.perform(get("/api/suggestions/team").with(signedInAs(ben))).andExpect(status().isForbidden());
        mvc.perform(get("/api/suggestions/mine").with(signedInAs(alice))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void preservesWeeklyAndPersonProposalsWhenAccepted() throws Exception {
        long draft = draft(alice);
        String weekly = line(room, 1).replace("}", ",\"recurrence\":{\"occurrences\":3}}");
        long s = number(write(ben, "/api/suggestions", "{\"lines\":[" + weekly + "]}")
                .andExpect(status().isCreated()), "$.id");
        accept(alice, s, "{\"requestId\":" + draft + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.recurrence.occurrences").value(3));
        mvc.perform(get("/api/requests/{id}/draft", draft).with(signedInAs(alice)))
                .andExpect(jsonPath("$.lines[0].quote").value(90.00));
        long person = jdbc.queryForObject("SELECT id FROM resource_type WHERE name = 'Person'", Long.class);
        long skill = testData().skill(unique("Java"));
        Instant firstDay = start.atZone(clock.getZone()).toLocalDate().atStartOfDay(clock.getZone()).toInstant();
        Instant afterLastDay = start.atZone(clock.getZone()).toLocalDate().plusDays(2).atStartOfDay(clock.getZone()).toInstant();
        String proposal = line(person, 2).replace(start.toString(), firstDay.toString())
                .replace(end.toString(), afterLastDay.toString()).replace("}", ",\"skills\":[" + skill + "]}");
        long personSuggestion = number(write(ben, "/api/suggestions", "{\"lines\":[" + proposal + "]}")
                .andExpect(status().isCreated()), "$.id");
        accept(alice, personSuggestion, "{\"requestId\":" + draft + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.skills[0]").value(skill));
        mvc.perform(get("/api/requests/{id}/draft", draft).with(signedInAs(alice)))
                .andExpect(jsonPath("$.lines[1].skills[0].id").value(skill))
                .andExpect(jsonPath("$.lines[1].quantity").value(2));
    }

    @Test
    void failedAcceptanceLeavesSuggestionOpenAndSendsNoDecisionNotice() throws Exception {
        long s = create(room, 1, null);
        long draft = draft(alice);
        movableClock.set(end.plusSeconds(3600));
        accept(alice, s, "{\"requestId\":" + draft + "}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_NOT_FUTURE"));
        mvc.perform(get("/api/suggestions/{id}", s).with(signedInAs(ben)))
                .andExpect(jsonPath("$.state").value("OPEN")).andExpect(jsonPath("$.decidedAt").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_line WHERE request_id = ?", Long.class, draft)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE member_id = ? AND kind = 'SUGGESTION'",
                Long.class, member)).isZero();
    }

    private long transactions() { return jdbc.queryForObject("SELECT count(*) FROM transaction WHERE team_id = ?", Long.class, team); }
    private String line(long type, int quantity) {
        return "{\"typeId\":" + type + ",\"quantity\":" + quantity + ",\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
    }
    private long create(long type, int quantity, String note) throws Exception {
        return number(write(ben, "/api/suggestions", "{\"lines\":[" + line(type, quantity) + "],\"note\":"
                + (note == null ? "null" : "\"" + note + "\"") + "}").andExpect(status().isCreated()), "$.id");
    }
    private long draft(String who) throws Exception { return number(write(who, "/api/requests", "{}"), "$.id"); }
    private ResultActions accept(String who, long s, String body) throws Exception {
        return write(who, "/api/suggestions/" + s + "/accept", body);
    }
    private ResultActions write(String who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(signedInAs(who)).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private long number(ResultActions result, String path) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path)).longValue();
    }
}
