package edu.cwru.openbench.members;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who the people are (design Section 4.1). Demo 1 has the reads: the signed-in Member, the Teams,
 * and the unscoped lookups other modules use for their own rules. The Administrator's writes come
 * in a later increment.
 *
 * <p>Methods a controller calls carry {@code @PreAuthorize}; methods only other modules call carry
 * no role check, because no controller reaches them.
 */
@Service
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository members;
    private final TeamRepository teams;
    private final MemberRoleRepository roles;

    MemberService(MemberRepository members, TeamRepository teams, MemberRoleRepository roles) {
        this.members = members;
        this.teams = teams;
        this.roles = roles;
    }

    /** The signed-in Member: GET /api/me and the body of a successful sign-in. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public MemberSummary me(Actor actor) {
        return summary(actor.memberId());
    }

    /** The active Teams, by name. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public List<TeamSummary> teams() {
        return teams.findByRetiredAtIsNullOrderByName().stream().map(Team::toSummary).toList();
    }

    /** (Unscoped) A Member's name, Team, active flag, and roles, for another module's rule. */
    public MemberSummary summary(MemberId id) {
        Member member = members.findById(id.value())
                .orElseThrow(() -> new NotFoundException("Member " + id.value() + " does not exist."));
        Team team = teams.findById(member.teamId()).orElseThrow();
        return new MemberSummary(new MemberId(member.id()), member.name(), member.username(),
                team.toSummary(), member.active(), roles.rolesOf(member.id()));
    }

    /** The Team's current Manager. */
    public MemberId managerOf(TeamId team) {
        Team found = teams.findById(team.value())
                .orElseThrow(() -> new NotFoundException("Team " + team.value() + " does not exist."));
        if (found.managerMemberId() == null) {
            throw new NotFoundException("Team " + team.value() + " has no Manager.");
        }
        return new MemberId(found.managerMemberId());
    }

    /** The active Members holding a role, by id; every active Member for {@link Role#MEMBER}. */
    public List<MemberId> withRole(Role role) {
        List<Long> ids = role == Role.MEMBER ? members.findActiveIds() : roles.findActiveMemberIdsWithRole(role);
        return ids.stream().map(MemberId::new).toList();
    }

    /** (Unscoped) A Team, retired or not, for another module's rule. */
    public TeamSummary team(TeamId id) {
        return teams.findById(id.value()).map(Team::toSummary)
                .orElseThrow(() -> new NotFoundException("Team " + id.value() + " does not exist."));
    }
}
