package edu.cwru.openbench.members;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The reads other modules use for their own rules; they carry no role check. */
class MemberServiceTest extends PostgresTest {

    @Autowired
    private MemberService members;

    private String teamName;
    private long team;
    private long manager;
    private long planner;
    private long departed;

    @BeforeEach
    void createATeam() {
        teamName = unique("Research");
        team = testData().team(teamName);
        manager = testData().member(team, unique("bob"), Role.MANAGER);
        planner = testData().member(team, unique("erin"), Role.PLANNER);
        departed = testData().member(team, unique("rita"), Role.PLANNER);
        testData().deactivate(departed);
    }

    @Test
    void summarizesAMemberWithTheirTeamAndRoles() {
        MemberSummary summary = members.summary(new MemberId(manager));

        assertThat(summary.id()).isEqualTo(new MemberId(manager));
        assertThat(summary.team()).isEqualTo(new TeamSummary(new TeamId(team), teamName));
        assertThat(summary.active()).isTrue();
        assertThat(summary.roles()).containsExactlyInAnyOrder(Role.MANAGER, Role.MEMBER);
        assertThat(members.summary(new MemberId(departed)).active()).isFalse();
    }

    @Test
    void refusesAnUnknownMember() {
        assertThatThrownBy(() -> members.summary(new MemberId(Long.MAX_VALUE)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void namesTheTeamsManager() {
        assertThat(members.managerOf(new TeamId(team))).isEqualTo(new MemberId(manager));

        long leaderless = testData().team(unique("Leaderless"));
        assertThatThrownBy(() -> members.managerOf(new TeamId(leaderless))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listsTheActiveMembersHoldingARole() {
        assertThat(members.withRole(Role.PLANNER)).contains(new MemberId(planner)).doesNotContain(new MemberId(departed));
        assertThat(members.withRole(Role.MEMBER))
                .contains(new MemberId(manager), new MemberId(planner))
                .doesNotContain(new MemberId(departed));
    }
}
