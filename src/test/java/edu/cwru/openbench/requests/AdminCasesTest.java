package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.BrowserSession;
import edu.cwru.openbench.Verifies;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The Administration test cases (docs/test-cases, Section "Administration") that issue #12 covers,
 * step by step through the API, on the Matched data of the test world with Carol Davis as its
 * Administrator.
 */
class AdminCasesTest extends MatchedWorldTest {

    private static final String WRONG_LOGIN = "The username or password is wrong.";

    @BeforeEach
    void makeCarolTheAdministrator() {
        jdbc.update("INSERT INTO member_role (member_id, team_id, role) VALUES (?, ?, 'ADMIN')", carolId, operations);
    }

    @Test
    @DisplayName("adm-member: create a Member who can sign in; a deactivated Member cannot")
    @Verifies("adm-member")
    void aMemberWorksOnlyWhileActive() throws Exception {
        LocalDate d = dayD();
        String nina = unique("nina");

        // 1. Create N1 under Members: listed in Operations with the role Member only.
        long ninaId = id(mvc.perform(post("/api/admin/members").with(as(carol))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Nina Lowe", "email": "%s@openbench.example", "username": "%s",
                                 "password": "start-2026", "teamId": %d}""".formatted(nina, nina, operations)))
                .andExpect(status().isCreated()));
        mvc.perform(get("/api/teams/{id}/members", operations).with(as(carol)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].name", ninaId).value("Nina Lowe"))
                .andExpect(jsonPath("$.content[?(@.id == %d)].active", ninaId).value(true))
                .andExpect(jsonPath("$.content[?(@.id == %d)].roles[*]", ninaId).value(contains("MEMBER")));

        // 2. Sign in as nina: the shell shows her name and Team; she adds her skill and availability.
        BrowserSession session = BrowserSession.signIn(mvc, nina, "start-2026");
        mvc.perform(get("/api/me").with(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nina Lowe"))
                .andExpect(jsonPath("$.team.id").value((int) operations));
        mvc.perform(put("/api/members/me/skills").with(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\": [" + statistics + "]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/members/me/availability").with(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ranges\": [{\"from\": \"%s\", \"to\": \"%s\"}]}"
                                .formatted(LocalDate.now(clock), d.plusDays(60))))
                .andExpect(status().isOk());

        // 3. A wrong password: BAD_CREDENTIALS, with the message of an unknown username.
        BrowserSession.attemptSignIn(mvc, nina, "wrong")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value(WRONG_LOGIN));
        BrowserSession.attemptSignIn(mvc, unique("nobody"), "start-2026")
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value(WRONG_LOGIN));

        // 4. Alice submits a line for Statistics, D to D+1: Dave Kim, Nina Lowe, Rita Gomez.
        shortlistOfANewStatisticsLine(d)
                .andExpect(jsonPath("$.candidates[*].name", contains("Dave Kim", "Nina Lowe", "Rita Gomez")));

        // 5. Carol deactivates Nina: she cannot sign in, and she is not on the new Shortlist.
        mvc.perform(post("/api/admin/members/{id}/deactivate", ninaId).with(as(carol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        BrowserSession.attemptSignIn(mvc, nina, "start-2026")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value(WRONG_LOGIN));
        mvc.perform(get("/api/me").with(session)).andExpect(status().isUnauthorized());
        shortlistOfANewStatisticsLine(d)
                .andExpect(jsonPath("$.candidates[*].name", contains("Dave Kim", "Rita Gomez")));
        // Her history is kept: her profile, as she left it, and her place on the roster.
        mvc.perform(get("/api/members/{id}/profile", ninaId).with(as(carol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[*].name", contains(statisticsName)))
                .andExpect(jsonPath("$.availability[0].to").value(d.plusDays(60).toString()));
        mvc.perform(get("/api/teams/{id}/members", operations).with(as(carol)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].active", ninaId).value(false));
    }

    @Test
    @DisplayName("adm-teams-roles: Teams, moves between Teams, and roles keep exactly one Manager per Team")
    @Verifies("adm-teams-roles")
    void everyTeamKeepsExactlyOneManager() throws Exception {
        long benId = world.ben().id();
        long ritaId = world.rita().id();
        long bobId = world.bob().id();
        String platformName = nameOf(platform);

        // 1. Create the Team Design, then rename it: listed as Product Design, with no Members yet.
        long design = id(mvc.perform(post("/api/admin/teams").with(as(carol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + unique("Design") + "\"}"))
                .andExpect(status().isCreated()));
        String productDesign = unique("Product Design");
        mvc.perform(put("/api/admin/teams/{id}", design).with(as(carol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + productDesign + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/teams").with(as(carol)))
                .andExpect(jsonPath("$[?(@.id == %d)].name", design).value(productDesign))
                .andExpect(jsonPath("$[?(@.id == %d)].activeMembers", design).value(0));
        mvc.perform(get("/api/teams/{id}/members", design).with(as(carol)))
                .andExpect(jsonPath("$.content", empty()));
        assertEveryTeamHasExactlyOneManager(design);

        // 2. Move Ben to Product Design and grant him Manager; Platform still has Alice. Both recorded.
        mvc.perform(post("/api/admin/members/{id}/move", benId).with(as(carol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": " + design + "}"))
                .andExpect(status().isOk());
        grant(benId, "MANAGER").andExpect(status().isOk());
        mvc.perform(get("/api/admin/teams").with(as(carol)))
                .andExpect(jsonPath("$[?(@.id == %d)].manager", design).value((int) benId))
                .andExpect(jsonPath("$[?(@.id == %d)].manager", platform).value((int) world.alice().id()));
        assertEveryTeamHasExactlyOneManager(design);
        List<Map<String, Object>> changes = changes(2);
        assertRecorded(changes.get(0), "ROLE_GRANTED", benId, "MEMBER", "MEMBER, MANAGER");
        assertRecorded(changes.get(1), "MEMBER_MOVED", benId, platformName, productDesign);

        // 3. Grant Manager to Rita: she replaces Bob, who keeps the role Member only.
        grant(ritaId, "MANAGER").andExpect(status().isOk());
        mvc.perform(get("/api/teams/{id}/members", research).with(as(carol)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].roles[*]", ritaId).value(containsInAnyOrder("MEMBER", "MANAGER")))
                .andExpect(jsonPath("$.content[?(@.id == %d)].roles[*]", bobId).value(contains("MEMBER")));
        assertThat(jdbc.queryForObject("SELECT manager_member_id FROM team WHERE id = ?", Long.class, research))
                .isEqualTo(ritaId);
        assertEveryTeamHasExactlyOneManager(design);
        changes = changes(2);
        assertRecorded(changes.get(0), "ROLE_GRANTED", ritaId, "MEMBER", "MEMBER, MANAGER");
        assertRecorded(changes.get(1), "ROLE_REVOKED", bobId, "MEMBER, MANAGER", "MEMBER");

        // 4. Grant and revoke Resource Owner for Rita: both recorded.
        grant(ritaId, "RESOURCE_OWNER")
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER", "MANAGER", "RESOURCE_OWNER")));
        mvc.perform(delete("/api/admin/roles/{member}/RESOURCE_OWNER", ritaId).with(as(carol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER", "MANAGER")));
        assertEveryTeamHasExactlyOneManager(design);
        changes = changes(2);
        assertRecorded(changes.get(0), "ROLE_REVOKED", ritaId, "MEMBER, MANAGER, RESOURCE_OWNER", "MEMBER, MANAGER");
        assertRecorded(changes.get(1), "ROLE_GRANTED", ritaId, "MEMBER, MANAGER", "MEMBER, MANAGER, RESOURCE_OWNER");

        // 5. Retire Product Design while Ben is still in it: refused, since only a Team without active
        //    Members is retired (TEAM_NOT_EMPTY).
        mvc.perform(post("/api/admin/teams/{id}/retire", design).with(as(carol)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_EMPTY"));
        // 6. Move Ben back to Platform (he leaves the Manager role behind), then retire Product Design.
        mvc.perform(post("/api/admin/members/{id}/move", benId).with(as(carol)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": " + platform + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("MEMBER")));
        assertEveryTeamHasExactlyOneManager(design);
        mvc.perform(post("/api/admin/teams/{id}/retire", design).with(as(carol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));
        assertEveryTeamHasExactlyOneManager(design);
        // Its history is kept: the Team and every recorded change to it stay readable.
        mvc.perform(get("/api/admin/teams").with(as(carol)))
                .andExpect(jsonPath("$[?(@.id == %d)].name", design).value(productDesign))
                .andExpect(jsonPath("$[?(@.id == %d)].retired", design).value(true));
        assertThat(jdbc.queryForList("SELECT kind FROM admin_change WHERE team_id = ? ORDER BY id", String.class, design))
                .containsExactly("TEAM_CREATED", "TEAM_RENAMED", "MEMBER_MOVED", "ROLE_GRANTED", "ROLE_REVOKED",
                        "TEAM_RETIRED");
    }

    // ---------------------------------------------------------------- Helpers

    private ResultActions shortlistOfANewStatisticsLine(LocalDate d) throws Exception {
        long draft = createDraft(as(alice));
        long line = personLine(as(alice), draft, 1, d, d.plusDays(1), statistics);
        submit(as(alice), draft).andExpect(status().isOk());
        return shortlist(as(alice), draft, line).andExpect(status().isOk());
    }

    private ResultActions grant(long member, String role) throws Exception {
        return mvc.perform(post("/api/admin/roles").with(as(carol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\": " + member + ", \"role\": \"" + role + "\"}"));
    }

    /** The newest entries of the change record, as GET /api/admin/changes returns them. */
    private List<Map<String, Object>> changes(int size) throws Exception {
        String body = mvc.perform(get("/api/admin/changes").param("size", String.valueOf(size)).with(as(carol)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.content");
    }

    /** Recorded with actor, old and new value, and timestamp. */
    private void assertRecorded(Map<String, Object> change, String kind, long member, String oldValue, String newValue) {
        assertThat(change)
                .containsEntry("kind", kind)
                .containsEntry("member", (int) member)
                .containsEntry("oldValue", oldValue)
                .containsEntry("newValue", newValue)
                .containsEntry("actor", (int) carolId)
                .containsEntry("actorName", "Carol Davis");
        assertThat(change.get("at")).isNotNull();
    }

    /**
     * The invariant of adm-teams-roles, on this world's Teams and {@code design}: a Team with active
     * Members has exactly one Manager, active and in the Team, held in {@code member_role} and in
     * {@code team.manager_member_id} alike; a Team without active Members has none.
     */
    private void assertEveryTeamHasExactlyOneManager(long design) {
        for (long team : new long[] {platform, research, operations, design}) {
            int active = jdbc.queryForObject("SELECT count(*) FROM member WHERE team_id = ? AND active", Integer.class, team);
            List<Long> managers = jdbc.queryForList(
                    "SELECT member_id FROM member_role WHERE team_id = ? AND role = 'MANAGER'", Long.class, team);
            Long column = jdbc.queryForObject("SELECT manager_member_id FROM team WHERE id = ?", Long.class, team);
            if (active == 0) {
                assertThat(managers).as("Managers of Team %d, which has no active Member", team).isEmpty();
                assertThat(column).isNull();
                continue;
            }
            assertThat(managers).as("Managers of Team %d", team).hasSize(1);
            assertThat(column).as("team.manager_member_id of Team %d", team).isEqualTo(managers.get(0));
            assertThat(jdbc.queryForObject("SELECT active AND team_id = ? FROM member WHERE id = ?", Boolean.class,
                    team, column)).as("The Manager of Team %d is active and in it", team).isTrue();
        }
    }

    private String nameOf(long team) {
        return jdbc.queryForObject("SELECT name FROM team WHERE id = ?", String.class, team);
    }
}
