package edu.cwru.openbench.members;

import edu.cwru.openbench.common.Role;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MemberRoleRepository extends JpaRepository<MemberRole, MemberRole.Key> {

    List<MemberRole> findByMemberId(Long memberId);

    /** The Member's stored roles; {@link Role#MEMBER} is never among them. */
    default Set<Role> rolesOf(Long memberId) {
        return findByMemberId(memberId).stream().map(MemberRole::role).collect(Collectors.toSet());
    }

    @Query("""
            select r.memberId from MemberRole r join Member m on m.id = r.memberId
            where r.role = :role and m.active
            order by r.memberId""")
    List<Long> findActiveMemberIdsWithRole(Role role);
}
