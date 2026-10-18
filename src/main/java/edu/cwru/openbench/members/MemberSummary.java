package edu.cwru.openbench.members;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.Role;
import java.util.EnumSet;
import java.util.Set;

/**
 * A Member as other modules see them, for their own rules: name, Team, whether active, and the
 * roles they hold when signed in ({@link Role#MEMBER} always included). Never carries the password.
 */
public record MemberSummary(MemberId id, String name, String username, TeamSummary team, boolean active, Set<Role> roles) {

    public MemberSummary {
        EnumSet<Role> all = EnumSet.of(Role.MEMBER);
        all.addAll(roles);
        roles = Set.copyOf(all);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }
}
