package edu.cwru.openbench.members;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.BrowserSession;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.TestData;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Administrator's operations on Members, Teams, and roles (design Section 4.1, the rules table):
 * each rule's refusal and each operation's effect, through the API.
 */
@DisplayName("Admin.Member.Create: Members, Teams, and roles")
class AdministrationTest extends PostgresTest {

    @Autowired
    private MemberService members;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private TransactionTemplate transactions;

    private long office;
    private String admin;
    private long adminId;
    private long platform;
    private String alice;
    private long aliceId;

    @BeforeEach
    void createAnAdministratorAndATeam() {
        office = testData().team(unique("Office"));
        admin = unique("carol");
        adminId = testData().member(office, admin, Role.ADMIN);
        platform = testData().team(unique("Platform"));
        alice = unique("alice");
        aliceId = testData().member(platform, alice, Role.MANAGER);
    }

    // ---------------------------------------------------------------- createMember

    @Test
    @DisplayName("SE-password: a new Member holds MEMBER only, can sign in, and their password is stored only as a bcrypt hash")
    void createsAMemberWhoCanSignIn() throws Exception {
        String username = unique("nina");
        String body = createMember(username, username + "@openbench.example", "start-2026", platform)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/members/")))
                .andExpect(jsonPath("$.name").value("Nina Lowe"))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.team.id").value((int) platform))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER")))
                .andExpect(content().string(not(containsString("start-2026"))))
                .andExpect(content().string(not(containsString("password"))))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        String hash = jdbc.queryForObject("SELECT password_hash FROM member WHERE id = ?", String.class, id);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain("start-2026");
        assertThat(passwords.matches("start-2026", hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_role WHERE member_id = ?", Integer.class, id)).isZero();
        assertThat(BrowserSession.signIn(mvc, username, "start-2026")).isNotNull();
        assertThat(lastChangeOf(id)).containsEntry("kind", "MEMBER_CREATED").containsEntry("actor_id", adminId);
    }

    @Test
    void refusesATakenUsernameOrEmail() throws Exception {
        createMember(alice, unique("other") + "@openbench.example", "start-2026", platform)
                .andExpect(refused(422, "USERNAME_TAKEN"));
        createMember(unique("nina"), alice.toUpperCase() + "@TEST.example", "start-2026", platform)
                .andExpect(refused(422, "EMAIL_TAKEN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE team_id = ?", Integer.class, platform))
                .isEqualTo(1);
    }

    @Test
    void refusesARetiredOrUnknownTeamAndAnInvalidBody() throws Exception {
        long retired = testData().team(unique("Retired"));
        jdbc.update("UPDATE team SET retired_at = now() WHERE id = ?", retired);
        String username = unique("nina");

        createMember(username, username + "@openbench.example", "start-2026", retired)
                .andExpect(refused(422, "TEAM_RETIRED"));
        createMember(username, username + "@openbench.example", "start-2026", Long.MAX_VALUE)
                .andExpect(refused(404, "NOT_FOUND"));
        createMember(username, username + "@openbench.example", "short", platform)
                .andExpect(refused(400, "VALIDATION_FAILED"));
        createMember(username, "not-an-email", "start-2026", platform)
                .andExpect(refused(400, "VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE username = ?", Integer.class, username)).isZero();
    }

    // ---------------------------------------------------------------- deactivate

    @Test
    @DisplayName("A deactivated Member cannot sign in, is on no Shortlist, and their history stays")
    void deactivatesAMemberAndKeepsTheirHistory() throws Exception {
        String ben = unique("ben");
        long benId = testData().member(platform, ben);
        long skill = testData().skill(unique("Statistics"));
        testData().holds(benId, skill);
        LocalDate today = LocalDate.now(movableClock);
        testData().available(benId, today, today.plusDays(60));
        long research = testData().team(unique("Research"));
        long loan = testData().loan(benId, platform, research, today.plusDays(30), today.plusDays(31));
        long request = testData().submittedRequest(platform, Instant.now(movableClock));
        DateRange window = new DateRange(today.plusDays(1), today.plusDays(2));
        assertThat(members.availableWithSkills(Set.of(new SkillId(skill)), window, new TeamId(research)))
                .contains(new MemberId(benId));

        postAs(admin, "/api/admin/members/" + benId + "/deactivate")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        BrowserSession.attemptSignIn(mvc, ben, TestData.PASSWORD)
                .andExpect(refused(401, "BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("The username or password is wrong."));
        assertThat(members.availableWithSkills(Set.of(new SkillId(skill)), window, new TeamId(research))).isEmpty();
        assertThat(members.isAvailable(new MemberId(benId), window)).isFalse();
        // History: the Loan, the Team's Request, and the profile stay readable.
        assertThat(jdbc.queryForObject("SELECT member_id FROM loan WHERE id = ?", Long.class, loan)).isEqualTo(benId);
        assertThat(jdbc.queryForObject("SELECT state FROM request WHERE id = ?", String.class, request)).isEqualTo("SUBMITTED");
        mvc.perform(get("/api/members/{id}/profile", benId).with(signedInAs(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].id").value((int) skill));
        mvc.perform(get("/api/teams/{id}/members", platform).with(signedInAs(alice)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].active", benId).value(false));
        assertThat(lastChangeOf(benId)).containsEntry("kind", "MEMBER_DEACTIVATED")
                .containsEntry("old_value", "active").containsEntry("new_value", "inactive");

        // Deactivating again changes nothing and records nothing.
        long recorded = changesOf(benId);
        postAs(admin, "/api/admin/members/" + benId + "/deactivate").andExpect(status().isOk());
        assertThat(changesOf(benId)).isEqualTo(recorded);
    }

    @Test
    void deactivatesAManagerOnlyAsTheTeamsLastActiveMember() throws Exception {
        long ben = testData().member(platform, unique("ben"));

        postAs(admin, "/api/admin/members/" + aliceId + "/deactivate").andExpect(refused(422, "TEAM_NEEDS_MANAGER"));
        assertThat(isActive(aliceId)).isTrue();

        postAs(admin, "/api/admin/members/" + ben + "/deactivate").andExpect(status().isOk());
        postAs(admin, "/api/admin/members/" + aliceId + "/deactivate")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER")));
        assertThat(isActive(aliceId)).isFalse();
        assertThat(managerOf(platform)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_role WHERE team_id = ? AND role = 'MANAGER'",
                Integer.class, platform)).isZero();
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("The last active Administrator is never deactivated and never loses ADMIN")
    void keepsTheLastActiveAdministrator() {
        Actor actor = new Actor(new MemberId(adminId), new TeamId(office), Set.of(Role.ADMIN));
        // Other tests' Administrators share the database: set them aside in a transaction that rolls back.
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update("DELETE FROM member_role WHERE role = 'ADMIN' AND member_id <> ?", adminId);

            assertThatThrownBy(() -> members.deactivate(actor, new MemberId(adminId)))
                    .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("LAST_ADMINISTRATOR"));
            assertThatThrownBy(() -> members.revokeRole(actor, new MemberId(adminId), Role.ADMIN))
                    .isInstanceOfSatisfying(RuleViolation.class, e -> assertThat(e.code()).isEqualTo("LAST_ADMINISTRATOR"));
            assertThat(isActive(adminId)).isTrue();
        });
    }

    @Test
    void anotherAdministratorMayBeDeactivatedOrLoseTheRole() throws Exception {
        long second = testData().member(office, unique("carl"), Role.ADMIN);
        long third = testData().member(office, unique("cora"), Role.ADMIN);

        mvc.perform(delete("/api/admin/roles/{member}/ADMIN", second).with(signedInAs(admin)).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER")));
        postAs(admin, "/api/admin/members/" + third + "/deactivate").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- grantRole and revokeRole

    @Test
    @DisplayName("Granting MANAGER replaces the Team's Manager in the same transaction")
    void grantingManagerReplacesTheTeamsManager() throws Exception {
        long ben = testData().member(platform, unique("ben"));

        grant(admin, ben, "MANAGER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER", "MANAGER")));

        assertThat(managerOf(platform)).isEqualTo(ben);
        assertThat(members.summary(new MemberId(aliceId)).roles()).containsExactly(Role.MEMBER);
        assertThat(jdbc.queryForList("SELECT member_id FROM member_role WHERE team_id = ? AND role = 'MANAGER'",
                Long.class, platform)).containsExactly(ben);
        assertThat(lastChangeOf(aliceId)).containsEntry("kind", "ROLE_REVOKED")
                .containsEntry("old_value", "MEMBER, MANAGER").containsEntry("new_value", "MEMBER");
        assertThat(lastChangeOf(ben)).containsEntry("kind", "ROLE_GRANTED")
                .containsEntry("old_value", "MEMBER").containsEntry("new_value", "MEMBER, MANAGER");

        // Granting a role the Member holds changes nothing.
        long recorded = changesOf(ben);
        grant(admin, ben, "MANAGER").andExpect(status().isOk());
        assertThat(changesOf(ben)).isEqualTo(recorded);
    }

    @Test
    void grantsManagerInATeamThatHasNone() throws Exception {
        long team = testData().team(unique("Design"));
        long ben = testData().member(team, unique("ben"));

        grant(admin, ben, "MANAGER").andExpect(status().isOk());
        assertThat(managerOf(team)).isEqualTo(ben);
    }

    @Test
    void refusesARoleForADeactivatedMemberAndTheRoleMember() throws Exception {
        long ben = testData().member(platform, unique("ben"));
        testData().deactivate(ben);

        grant(admin, ben, "RESOURCE_OWNER").andExpect(refused(422, "MEMBER_INACTIVE"));
        grant(admin, aliceId, "MEMBER").andExpect(refused(400, "VALIDATION_FAILED"));
        grant(admin, Long.MAX_VALUE, "PLANNER").andExpect(refused(404, "NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_role WHERE member_id = ?", Integer.class, ben)).isZero();
    }

    @Test
    void revokesARoleButNeverManager() throws Exception {
        long dave = testData().member(platform, unique("dave"), Role.RESOURCE_OWNER);

        mvc.perform(delete("/api/admin/roles/{member}/MANAGER", aliceId).with(signedInAs(admin)).with(xsrf()))
                .andExpect(refused(422, "TEAM_NEEDS_MANAGER"));
        assertThat(managerOf(platform)).isEqualTo(aliceId);

        mvc.perform(delete("/api/admin/roles/{member}/RESOURCE_OWNER", dave).with(signedInAs(admin)).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER")));
        assertThat(lastChangeOf(dave)).containsEntry("kind", "ROLE_REVOKED")
                .containsEntry("old_value", "MEMBER, RESOURCE_OWNER").containsEntry("new_value", "MEMBER");

        // Revoking a role the Member does not hold changes nothing.
        long recorded = changesOf(dave);
        mvc.perform(delete("/api/admin/roles/{member}/PLANNER", dave).with(signedInAs(admin)).with(xsrf()))
                .andExpect(status().isOk());
        assertThat(changesOf(dave)).isEqualTo(recorded);
    }

    // ---------------------------------------------------------------- Teams

    @Test
    void createsRenamesAndRetiresATeam() throws Exception {
        String name = unique("Design");
        String body = postJson(admin, "/api/admin/teams", "{\"name\": \"" + name + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.retired").value(false))
                .andExpect(jsonPath("$.activeMembers").value(0))
                .andReturn().getResponse().getContentAsString();
        long team = ((Number) JsonPath.read(body, "$.id")).longValue();

        String renamed = unique("Product Design");
        mvc.perform(put("/api/admin/teams/{id}", team).with(signedInAs(admin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + renamed + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(renamed));
        mvc.perform(get("/api/teams").with(signedInAs(admin)))
                .andExpect(jsonPath("$[?(@.id == %d)].name", team).value(renamed));

        postAs(admin, "/api/admin/teams/" + team + "/retire")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));
        mvc.perform(get("/api/teams").with(signedInAs(admin)))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) team))));
        mvc.perform(get("/api/admin/teams").with(signedInAs(admin)))
                .andExpect(jsonPath("$[?(@.id == %d)].retired", team).value(true));
        assertThat(lastTeamChangeOf(team)).containsEntry("kind", "TEAM_RETIRED");
    }

    @Test
    void refusesATakenTeamNameIgnoringCase() throws Exception {
        String taken = jdbc.queryForObject("SELECT name FROM team WHERE id = ?", String.class, platform);
        long other = testData().team(unique("Research"));

        postJson(admin, "/api/admin/teams", "{\"name\": \"" + taken.toLowerCase() + "\"}")
                .andExpect(refused(422, "TEAM_NAME_TAKEN"));
        mvc.perform(put("/api/admin/teams/{id}", other).with(signedInAs(admin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + taken.toUpperCase() + "\"}"))
                .andExpect(refused(422, "TEAM_NAME_TAKEN"));
        mvc.perform(put("/api/admin/teams/{id}", Long.MAX_VALUE).with(signedInAs(admin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + unique("X") + "\"}"))
                .andExpect(refused(404, "NOT_FOUND"));
        // A Team may change only the case of its own name.
        mvc.perform(put("/api/admin/teams/{id}", platform).with(signedInAs(admin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + taken.toUpperCase() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void retiresATeamOnlyWithoutActiveMembers() throws Exception {
        postAs(admin, "/api/admin/teams/" + platform + "/retire").andExpect(refused(422, "TEAM_NOT_EMPTY"));
        assertThat(jdbc.queryForObject("SELECT retired_at FROM team WHERE id = ?", Instant.class, platform)).isNull();

        postAs(admin, "/api/admin/members/" + aliceId + "/deactivate").andExpect(status().isOk());
        postAs(admin, "/api/admin/teams/" + platform + "/retire").andExpect(status().isOk());
        // A retired Team's roster stays readable.
        mvc.perform(get("/api/teams/{id}/members", platform).with(signedInAs(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value((int) aliceId));
    }

    // ---------------------------------------------------------------- The change record

    @Test
    void readsTheChangeRecordNewestFirstWithTheActorAndBothValues() throws Exception {
        long research = testData().team(unique("Research"));
        long ben = testData().member(platform, unique("ben"));
        jdbc.update("UPDATE member SET name = 'Ben Ortiz' WHERE id = ?", ben);
        jdbc.update("UPDATE member SET name = 'Carol Davis' WHERE id = ?", adminId);
        String platformName = jdbc.queryForObject("SELECT name FROM team WHERE id = ?", String.class, platform);
        String researchName = jdbc.queryForObject("SELECT name FROM team WHERE id = ?", String.class, research);

        postJson(admin, "/api/admin/members/" + ben + "/move", "{\"teamId\": " + research + "}").andExpect(status().isOk());
        grant(admin, ben, "RESOURCE_OWNER").andExpect(status().isOk());

        mvc.perform(get("/api/admin/changes").param("size", "2").with(signedInAs(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content[0].kind").value("ROLE_GRANTED"))
                .andExpect(jsonPath("$.content[0].member").value((int) ben))
                .andExpect(jsonPath("$.content[0].memberName").value("Ben Ortiz"))
                .andExpect(jsonPath("$.content[0].teamName").value(researchName))
                .andExpect(jsonPath("$.content[0].oldValue").value("MEMBER"))
                .andExpect(jsonPath("$.content[0].newValue").value("MEMBER, RESOURCE_OWNER"))
                .andExpect(jsonPath("$.content[0].actor").value((int) adminId))
                .andExpect(jsonPath("$.content[0].actorName").value("Carol Davis"))
                .andExpect(jsonPath("$.content[0].at").isNotEmpty())
                .andExpect(jsonPath("$.content[1].kind").value("MEMBER_MOVED"))
                .andExpect(jsonPath("$.content[1].oldValue").value(platformName))
                .andExpect(jsonPath("$.content[1].newValue").value(researchName));
    }

    // ---------------------------------------------------------------- Roles

    /** Each Administrator call, run by a Member of each other role. */
    @ParameterizedTest(name = "{0} {1} as {2}")
    @CsvSource(delimiter = '|', value = {
        "POST   | /api/admin/members                      | MEMBER         | {\"name\": \"X\", \"email\": \"x@x.example\", \"username\": \"x\", \"password\": \"start-2026\", \"teamId\": {team}}",
        "POST   | /api/admin/members                      | MANAGER        | {\"name\": \"X\", \"email\": \"x@x.example\", \"username\": \"x\", \"password\": \"start-2026\", \"teamId\": {team}}",
        "POST   | /api/admin/members/{member}/deactivate  | PLANNER        | ",
        "POST   | /api/admin/members/{member}/deactivate  | MANAGER        | ",
        "POST   | /api/admin/members/{member}/move        | RESOURCE_OWNER | {\"teamId\": {office}}",
        "POST   | /api/admin/roles                        | MANAGER        | {\"memberId\": {member}, \"role\": \"ADMIN\"}",
        "POST   | /api/admin/roles                        | PLANNER        | {\"memberId\": {member}, \"role\": \"PLANNER\"}",
        "DELETE | /api/admin/roles/{member}/RESOURCE_OWNER | MEMBER        | ",
        "POST   | /api/admin/teams                        | PLANNER        | {\"name\": \"X\"}",
        "PUT    | /api/admin/teams/{team}                 | MANAGER        | {\"name\": \"X\"}",
        "POST   | /api/admin/teams/{team}/retire          | RESOURCE_OWNER | ",
        "GET    | /api/admin/teams                        | MANAGER        | ",
        "GET    | /api/admin/changes                      | PLANNER        | "})
    @DisplayName("SE-roles: every Administrator call by a non-Administrator is refused with 403 FORBIDDEN and changes nothing")
    void refusesEveryoneButTheAdministrator(String method, String path, String role, String body) throws Exception {
        long team = testData().team(unique("Team"));
        long member = testData().member(team, unique("dave"), Role.RESOURCE_OWNER);
        String caller = unique("caller");
        testData().member(testData().team(unique("Callers")), caller, Role.valueOf(role));
        String uri = path.replace("{member}", String.valueOf(member)).replace("{team}", String.valueOf(team));
        MockHttpServletRequestBuilder request = switch (method) {
            case "POST" -> post(uri);
            case "PUT" -> put(uri);
            case "DELETE" -> delete(uri);
            default -> get(uri);
        };
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body
                    .replace("{member}", String.valueOf(member))
                    .replace("{team}", String.valueOf(team))
                    .replace("{office}", String.valueOf(office)));
        }

        mvc.perform(request.with(signedInAs(caller)).with(xsrf())).andExpect(refused(403, "FORBIDDEN"));

        MemberSummary unchanged = members.summary(new MemberId(member));
        assertThat(unchanged.active()).isTrue();
        assertThat(unchanged.team().id()).isEqualTo(new TeamId(team));
        assertThat(unchanged.roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.RESOURCE_OWNER);
        assertThat(jdbc.queryForMap("SELECT name, retired_at FROM team WHERE id = ?", team))
                .containsEntry("retired_at", null).doesNotContainEntry("name", "X");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE username = 'x'", Integer.class)).isZero();
        assertThat(changesOf(member)).isZero();
    }

    // ---------------------------------------------------------------- Helpers

    private ResultActions createMember(String username, String email, String password, long team) throws Exception {
        return postJson(admin, "/api/admin/members", """
                {"name": "Nina Lowe", "email": "%s", "username": "%s", "password": "%s", "teamId": %d}"""
                .formatted(email, username, password, team));
    }

    private ResultActions grant(String caller, long member, String role) throws Exception {
        return postJson(caller, "/api/admin/roles", "{\"memberId\": " + member + ", \"role\": \"" + role + "\"}");
    }

    private ResultActions postAs(String caller, String path) throws Exception {
        return mvc.perform(post(path).with(signedInAs(caller)).with(xsrf()));
    }

    private ResultActions postJson(String caller, String path, String body) throws Exception {
        return mvc.perform(post(path).with(signedInAs(caller)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static ResultMatcher refused(int status, String code) {
        return result -> {
            status().is(status).match(result);
            jsonPath("$.code").value(code).match(result);
        };
    }

    private boolean isActive(long member) {
        return jdbc.queryForObject("SELECT active FROM member WHERE id = ?", Boolean.class, member);
    }

    private Long managerOf(long team) {
        return jdbc.queryForObject("SELECT manager_member_id FROM team WHERE id = ?", Long.class, team);
    }

    private long changesOf(long member) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_change WHERE member_id = ?", Long.class, member);
    }

    private Map<String, Object> lastChangeOf(long member) {
        return jdbc.queryForMap("SELECT * FROM admin_change WHERE member_id = ? ORDER BY id DESC LIMIT 1", member);
    }

    private Map<String, Object> lastTeamChangeOf(long team) {
        return jdbc.queryForMap("SELECT * FROM admin_change WHERE team_id = ? ORDER BY id DESC LIMIT 1", team);
    }
}
