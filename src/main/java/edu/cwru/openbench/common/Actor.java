package edu.cwru.openbench.common;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The signed-in Member on whose behalf a call runs. A controller declares an {@code Actor} parameter
 * and receives the caller (resolved from the session by {@code security}); it passes the Actor to
 * the service, whose queries scope rows by its fields. {@link #roles()} always contains
 * {@link Role#MEMBER}.
 */
public record Actor(MemberId memberId, TeamId teamId, Set<Role> roles) implements Serializable {

    public Actor {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(teamId, "teamId");
        EnumSet<Role> all = EnumSet.of(Role.MEMBER);
        all.addAll(roles);
        roles = Set.copyOf(all);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }
}
