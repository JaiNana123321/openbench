package edu.cwru.openbench.security;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.members.MemberSummary;
import edu.cwru.openbench.members.TeamSummary;
import java.util.Comparator;
import java.util.List;

/**
 * The body of GET /api/me and of a successful sign-in, a contract with the web client:
 * {@code {"id": 1, "name": "Alice Chen", "username": "alice", "team": {"id": 1, "name": "Platform"},
 * "roles": ["MANAGER", "MEMBER"]}}. Roles are names in alphabetical order and include MEMBER.
 */
record MeResponse(MemberId id, String name, String username, TeamSummary team, List<Role> roles) {

    static MeResponse of(MemberSummary member) {
        List<Role> roles = member.roles().stream().sorted(Comparator.comparing(Role::name)).toList();
        return new MeResponse(member.id(), member.name(), member.username(), member.team(), roles);
    }
}
