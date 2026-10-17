package edu.cwru.openbench.members;

import edu.cwru.openbench.common.Actor;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * The principal of a signed-in Member's session: Spring Security's user, whose authorities are the
 * Member's roles by name, plus the caller as an {@link Actor} for controllers.
 */
public final class SignedInMember extends User {

    private static final long serialVersionUID = 1L;

    private final Actor actor;

    SignedInMember(Actor actor, String username, String passwordHash, boolean active) {
        super(username, passwordHash, active, true, true, true, authorities(actor));
        this.actor = actor;
    }

    public Actor actor() {
        return actor;
    }

    private static List<GrantedAuthority> authorities(Actor actor) {
        return actor.roles().stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.name())).toList();
    }
}
