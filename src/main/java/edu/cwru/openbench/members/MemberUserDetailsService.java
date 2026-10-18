package edu.cwru.openbench.members;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.TeamId;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The login lookup Spring Security installs: loads a Member by username with their roles. A
 * deactivated Member is reported disabled, so their sign-in fails. The only reader of the hash.
 */
@Component
class MemberUserDetailsService implements UserDetailsService {

    private final MemberRepository members;
    private final MemberRoleRepository roles;

    MemberUserDetailsService(MemberRepository members, MemberRoleRepository roles) {
        this.members = members;
        this.roles = roles;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        Member member = members.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No Member has this username."));
        Actor actor = new Actor(new MemberId(member.id()), new TeamId(member.teamId()), roles.rolesOf(member.id()));
        return new SignedInMember(actor, member.username(), member.passwordHash(), member.active());
    }
}
