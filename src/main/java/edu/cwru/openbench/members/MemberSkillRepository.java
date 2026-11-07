package edu.cwru.openbench.members;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface MemberSkillRepository extends JpaRepository<MemberSkillRow, MemberSkillRow.Key> {

    List<MemberSkillRow> findByMemberId(Long memberId);

    /** The ids of the skills the Member holds. */
    default Set<Long> skillIdsOf(Long memberId) {
        return findByMemberId(memberId).stream().map(MemberSkillRow::skillId).collect(Collectors.toSet());
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemberSkillRow s where s.memberId = :memberId")
    void deleteByMemberId(Long memberId);
}
