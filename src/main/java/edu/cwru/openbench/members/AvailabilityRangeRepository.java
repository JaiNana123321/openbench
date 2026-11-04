package edu.cwru.openbench.members;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AvailabilityRangeRepository extends JpaRepository<AvailabilityRangeRow, Long> {

    List<AvailabilityRangeRow> findByMemberIdOrderByFromDate(Long memberId);

    /** True when the Member is active and one of their ranges contains every day from {@code from} to {@code to}. */
    @Query("""
            select count(a) > 0 from AvailabilityRangeRow a join Member m on m.id = a.memberId
            where a.memberId = :memberId and m.active and a.fromDate <= :from and a.toDate >= :to""")
    boolean activeAndAvailable(Long memberId, LocalDate from, LocalDate to);

    /**
     * The active Members outside the Team who hold every one of the {@code skillCount} skills and
     * whose ranges contain the days. {@code skillIds} must not be empty; pass an id no skill has
     * with a count of 0 to ask for no skill at all.
     */
    @Query("""
            select m.id from Member m
            where m.active and m.teamId <> :excludeTeam
              and exists (select a.id from AvailabilityRangeRow a
                          where a.memberId = m.id and a.fromDate <= :from and a.toDate >= :to)
              and (select count(s) from MemberSkillRow s
                   where s.memberId = m.id and s.skillId in :skillIds) = :skillCount
            order by m.id""")
    List<Long> activeAvailableWithSkills(Collection<Long> skillIds, long skillCount, LocalDate from, LocalDate to,
            Long excludeTeam);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AvailabilityRangeRow a where a.memberId = :memberId")
    void deleteByMemberId(Long memberId);
}
