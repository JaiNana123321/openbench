package edu.cwru.openbench.members;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.TestData.Login;
import edu.cwru.openbench.TestData.MatchedWorld;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The Member Profiles test cases (docs/test-cases, Section "Member Profiles") through the API, on
 * the Matched data of the test world, and the refusals of each profile rule.
 */
class ProfilesTest extends PostgresTest {

    @Autowired
    private Clock clock;

    @Autowired
    private MemberService members;

    private MatchedWorld world;
    private LocalDate today;
    private LocalDate d;

    @BeforeEach
    void createTheMatchedData() {
        world = testData().matchedWorld(LocalDate.now(clock));
        today = world.today();
        d = world.d();
    }

    @Test
    @Verifies(value = "prf-skills", partial = true)
    @DisplayName("prf-skills: a Member maintains their own skills from the skill list, and not their Team")
    void aMemberMaintainsTheirOwnSkillsFromTheList() throws Exception {
        Login ben = world.ben();

        // Step 1: My profile.
        myProfile(ben)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ben Ortiz"))
                .andExpect(jsonPath("$.email").value(ben.username() + "@test.example"))
                .andExpect(jsonPath("$.team.id").value((int) world.platform()))
                .andExpect(jsonPath("$.skills[*].name", contains("Java")))
                .andExpect(jsonPath("$.availability", hasSize(1)))
                .andExpect(jsonPath("$.availability[0].from").value(today.toString()))
                .andExpect(jsonPath("$.availability[0].to").value(d.plusDays(60).toString()));

        // Step 2: add Statistics, chosen from the list.
        putSkills(ben, "[%d, %d]".formatted(world.java(), world.statistics()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[*].name", contains("Java", "Statistics")));

        // Step 3: no free text, and no skill that is not on the list.
        putSkills(ben, "[%d, \"Kotlin\"]".formatted(world.java()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putSkills(ben, "[%d, %d]".formatted(world.java(), Long.MAX_VALUE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Step 4: the Team is not the Member's to change.
        mvc.perform(post("/api/admin/members/{id}/move", ben.id()).with(signedInAs(ben.username())).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": %d}".formatted(world.research())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Pass criteria (step 5, the Shortlist, waits for Matched allocation).
        myProfile(ben)
                .andExpect(jsonPath("$.skills[*].name", contains("Java", "Statistics")))
                .andExpect(jsonPath("$.team.id").value((int) world.platform()));
    }

    @Test
    @Verifies(value = "prf-availability", partial = true)
    @DisplayName("prf-availability: a Member is available only when one range holds every day of the window")
    void availabilityRangesDecideWhetherAMemberIsAvailable() throws Exception {
        Login dave = world.dave();

        // Step 1: replace the range with two ranges.
        putAvailability(dave, dave.id(), ranges(today, d, d.plusDays(2), d.plusDays(60)))
                .andExpect(status().isOk());
        myProfile(dave)
                .andExpect(jsonPath("$.availability", hasSize(2)))
                .andExpect(jsonPath("$.availability[0].from").value(today.toString()))
                .andExpect(jsonPath("$.availability[0].to").value(d.toString()))
                .andExpect(jsonPath("$.availability[1].from").value(d.plusDays(2).toString()))
                .andExpect(jsonPath("$.availability[1].to").value(d.plusDays(60).toString()));

        // Steps 2 and 3 at the service level: M1 (D to D+1) and M2 (D+2 to D+3) for Platform.
        DateRange m1 = new DateRange(d, d.plusDays(1));
        DateRange m2 = new DateRange(d.plusDays(2), d.plusDays(3));
        MemberId daveId = new MemberId(dave.id());
        MemberId ritaId = new MemberId(world.rita().id());
        assertThat(members.isAvailable(daveId, m1)).isFalse();
        assertThat(members.isAvailable(daveId, m2)).isTrue();

        Set<SkillId> statistics = Set.of(new SkillId(world.statistics()));
        TeamId platform = new TeamId(world.platform());
        assertThat(members.availableWithSkills(statistics, m1, platform)).contains(ritaId).doesNotContain(daveId);
        assertThat(members.availableWithSkills(statistics, m2, platform)).contains(daveId, ritaId);
    }

    @Test
    @Verifies(value = "prf-visibility", partial = true)
    @DisplayName("prf-visibility: profiles are seen by the Member and their own Manager only")
    void profilesAreSeenByTheMemberAndTheirOwnManagerOnly() throws Exception {
        Login alice = world.alice();
        Login ben = world.ben();

        // Step 1: the Team view and Ben's profile, with no other personal data (Loans wait for #6).
        mvc.perform(get("/api/teams/{id}/members", world.platform()).with(signedInAs(alice.username())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("Alice Chen", "Ben Ortiz")));
        mvc.perform(get("/api/members/{id}/profile", ben.id()).with(signedInAs(alice.username())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.id").value((int) ben.id()))
                .andExpect(jsonPath("$.name").value("Ben Ortiz"))
                .andExpect(jsonPath("$.email").value(ben.username() + "@test.example"))
                .andExpect(jsonPath("$.team.id").value((int) world.platform()))
                .andExpect(jsonPath("$.skills[*].name", contains("Java")))
                .andExpect(jsonPath("$.availability", hasSize(1)));

        // Step 2: Alice adds D+70 to D+80 on Ben's behalf; Ben sees it.
        putAvailability(alice, ben.id(), ranges(today, d.plusDays(60), d.plusDays(70), d.plusDays(80)))
                .andExpect(status().isOk());
        myProfile(ben)
                .andExpect(jsonPath("$.availability", hasSize(2)))
                .andExpect(jsonPath("$.availability[1].from").value(d.plusDays(70).toString()))
                .andExpect(jsonPath("$.availability[1].to").value(d.plusDays(80).toString()));

        // Step 3: Rita is not in Alice's Team.
        mvc.perform(get("/api/members/{id}/profile", world.rita().id()).with(signedInAs(alice.username())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Step 4: a Member sees only their own profile.
        mvc.perform(get("/api/members/{id}/profile", world.dave().id()).with(signedInAs(ben.username())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Step 5: another Team's Manager cannot edit Ben's availability, and learns nothing.
        putAvailability(world.bob(), ben.id(), ranges(d, d))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        myProfile(ben).andExpect(jsonPath("$.availability", hasSize(2)));
    }

    @Test
    @Verifies(value = "adm-skills-no-policy", partial = true)
    @DisplayName("adm-skills-no-policy: the Administrator adds a skill, which Members can then choose")
    void theAdministratorAddsASkillMembersCanChoose() throws Exception {
        String carol = administrator();
        String python = unique("Python");

        // Step 1 (step 3, the Day Rate, waits for the Capacity Planner settings, #13).
        int id = addSkill(carol, python)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(python))
                .andExpect(jsonPath("$.retired").value(false))
                .andReturn().getResponse().getContentAsString().transform(json -> JsonPath.<Integer>read(json, "$.id"));
        mvc.perform(get("/api/skills").with(signedInAs(world.ben().username())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(python)));
        putSkills(world.ben(), "[%d, %d]".formatted(world.java(), id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[*].name", hasItem(python)));
    }

    // ---------------------------------------------------------------- Refusals and rules

    @Test
    void aSkillNameIsTakenIgnoringCase() throws Exception {
        addSkill(administrator(), "  sTaTiStIcS ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SKILL_NAME_TAKEN"));
        addSkill(administrator(), " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void onlyAnAdministratorMaintainsTheSkillList() throws Exception {
        addSkill(world.alice().username(), unique("Rust"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/skills/{id}/retire", world.java())
                        .with(signedInAs(world.ben().username())).with(xsrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRetiredSkillIsKeptByItsHoldersButNoLongerOffered() throws Exception {
        String carol = administrator();
        long welding = testData().skill(unique("Welding"));
        testData().holds(world.rita().id(), welding);

        mvc.perform(post("/api/admin/skills/{id}/retire", welding).with(signedInAs(carol)).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));
        mvc.perform(post("/api/admin/skills/{id}/retire", welding).with(signedInAs(carol)).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));

        mvc.perform(get("/api/skills").with(signedInAs(world.ben().username())))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) welding))));
        mvc.perform(get("/api/skills").param("includeRetired", "true").with(signedInAs(carol)))
                .andExpect(jsonPath("$[?(@.id == %d)].retired".formatted(welding)).value(true));

        putSkills(world.ben(), "[%d]".formatted(welding))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SKILL_RETIRED"));
        putSkills(world.rita(), "[%d, %d]".formatted(world.statistics(), welding))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[?(@.id == %d)].retired".formatted(welding)).value(true));
    }

    @Test
    void retiringAnUnknownSkillIsNotFound() throws Exception {
        mvc.perform(post("/api/admin/skills/{id}/retire", Long.MAX_VALUE).with(signedInAs(administrator())).with(xsrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefusedAndNothingChanges() throws Exception {
        Login dave = world.dave();
        putAvailability(dave, dave.id(), ranges(d, d.plusDays(3), d.plusDays(9), d.plusDays(8)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGE_INVALID"));
        mvc.perform(put("/api/members/me/availability").with(signedInAs(dave.username())).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ranges\": [{\"from\": \"%s\"}]}".formatted(d)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        myProfile(dave)
                .andExpect(jsonPath("$.availability", hasSize(1)))
                .andExpect(jsonPath("$.availability[0].from").value(today.toString()));
    }

    @Test
    void savedRangesThatOverlapOrTouchAreMerged() throws Exception {
        Login dave = world.dave();
        mvc.perform(put("/api/members/me/availability").with(signedInAs(dave.username())).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ranges(d.plusDays(10), d.plusDays(12), d, d.plusDays(4), d.plusDays(3), d.plusDays(5),
                                d.plusDays(6), d.plusDays(7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability", hasSize(2)))
                .andExpect(jsonPath("$.availability[0].from").value(d.toString()))
                .andExpect(jsonPath("$.availability[0].to").value(d.plusDays(7).toString()))
                .andExpect(jsonPath("$.availability[1].from").value(d.plusDays(10).toString()))
                .andExpect(jsonPath("$.availability[1].to").value(d.plusDays(12).toString()));

        putAvailability(dave, dave.id(), "{\"ranges\": []}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability", hasSize(0)));
    }

    @Test
    void aPlannerAndAnAdministratorSeeAProfileButDoNotEditIt() throws Exception {
        String planner = unique("erin");
        testData().member(world.operations(), planner, Role.PLANNER);
        String carol = administrator();
        for (String viewer : new String[] {planner, carol}) {
            mvc.perform(get("/api/members/{id}/profile", world.ben().id()).with(signedInAs(viewer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Ben Ortiz"));
            putAvailability(viewer, world.ben().id(), ranges(d, d))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void anUnknownMembersProfileIsNotFound() throws Exception {
        mvc.perform(get("/api/members/{id}/profile", Long.MAX_VALUE).with(signedInAs(administrator())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aRosterIsForTheTeamsOwnManagerAPlannerOrAnAdministrator() throws Exception {
        mvc.perform(get("/api/teams/{id}/members", world.research()).with(signedInAs(world.alice().username())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/teams/{id}/members", world.platform()).with(signedInAs(world.ben().username())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/teams/{id}/members", world.research()).with(signedInAs(administrator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("Bob Patel", "Rita Gomez")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/teams/{id}/members", Long.MAX_VALUE).with(signedInAs(administrator())))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- Helpers

    private String administrator() {
        String username = unique("carol");
        testData().member(world.operations(), username, Role.ADMIN);
        return username;
    }

    private ResultActions myProfile(Login member) throws Exception {
        return mvc.perform(get("/api/members/me/profile").with(signedInAs(member.username())));
    }

    private ResultActions putSkills(Login member, String skillIds) throws Exception {
        return mvc.perform(put("/api/members/me/skills").with(signedInAs(member.username())).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"skills\": " + skillIds + "}"));
    }

    private ResultActions putAvailability(Login caller, long memberId, String body) throws Exception {
        return putAvailability(caller.username(), memberId, body);
    }

    private ResultActions putAvailability(String caller, long memberId, String body) throws Exception {
        return mvc.perform(put("/api/members/{id}/availability", memberId).with(signedInAs(caller)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions addSkill(String caller, String name) throws Exception {
        return mvc.perform(post("/api/admin/skills").with(signedInAs(caller)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}"));
    }

    /** The body of an availability update with these days, in pairs of first and last day. */
    private static String ranges(LocalDate... days) {
        StringBuilder json = new StringBuilder("{\"ranges\": [");
        for (int i = 0; i < days.length; i += 2) {
            json.append(i == 0 ? "" : ", ")
                    .append("{\"from\": \"%s\", \"to\": \"%s\"}".formatted(days[i], days[i + 1]));
        }
        return json.append("]}").toString();
    }
}
