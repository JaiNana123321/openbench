package edu.cwru.openbench.members;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** An Administrator moves a Member to another Team; nobody else changes a Member's Team. */
class MoveToTeamTest extends PostgresTest {

    @Autowired
    private MemberService members;

    private long platform;
    private long research;
    private String admin;

    @BeforeEach
    void createTwoTeams() {
        platform = testData().team(unique("Platform"));
        research = testData().team(unique("Research"));
        testData().member(research, unique("bob"), Role.MANAGER);
        admin = unique("carol");
        testData().member(research, admin, Role.ADMIN);
    }

    @Test
    void movesAMemberWithTheirRoles() throws Exception {
        long dave = testData().member(platform, unique("dave"), Role.RESOURCE_OWNER);

        move(admin, dave, research)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.id").value((int) research));

        MemberSummary moved = members.summary(new MemberId(dave));
        assertThat(moved.team().id()).isEqualTo(new TeamId(research));
        assertThat(moved.roles()).contains(Role.RESOURCE_OWNER);
        move(admin, dave, research).andExpect(status().isOk());
    }

    @Test
    void aManagerLeavesOnlyAsTheTeamsLastActiveMemberAndLeavesTheRoleBehind() throws Exception {
        long alice = testData().member(platform, unique("alice"), Role.MANAGER);
        long ben = testData().member(platform, unique("ben"));

        move(admin, alice, research)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEAM_NEEDS_MANAGER"));

        testData().deactivate(ben);
        move(admin, alice, research).andExpect(status().isOk());
        MemberSummary moved = members.summary(new MemberId(alice));
        assertThat(moved.team().id()).isEqualTo(new TeamId(research));
        assertThat(moved.roles()).doesNotContain(Role.MANAGER);
        assertThat(jdbc.queryForObject("SELECT manager_member_id FROM team WHERE id = ?", Long.class, platform)).isNull();
    }

    @Test
    void refusesARetiredOrUnknownTeam() throws Exception {
        long ben = testData().member(platform, unique("ben"));
        jdbc.update("UPDATE team SET retired_at = now() WHERE id = ?", research);

        move(admin, ben, research)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEAM_RETIRED"));
        move(admin, ben, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(members.summary(new MemberId(ben)).team().id()).isEqualTo(new TeamId(platform));
    }

    @Test
    void aMemberCannotChangeTheirOwnTeam() throws Exception {
        String ben = unique("ben");
        long id = testData().member(platform, ben);

        move(ben, id, research)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(members.summary(new MemberId(id)).team().id()).isEqualTo(new TeamId(platform));
    }

    private ResultActions move(String caller, long memberId, long teamId) throws Exception {
        return mvc.perform(post("/api/admin/members/{id}/move", memberId).with(signedInAs(caller)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\": " + teamId + "}"));
    }
}
