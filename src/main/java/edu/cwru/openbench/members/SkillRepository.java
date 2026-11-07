package edu.cwru.openbench.members;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SkillRepository extends JpaRepository<SkillRow, Long> {

    @Query("select s from SkillRow s order by lower(s.name)")
    List<SkillRow> findAllByName();

    @Query("select s from SkillRow s where s.retiredAt is null order by lower(s.name)")
    List<SkillRow> findOfferedByName();

    /** True when a skill, retired or not, has this name ignoring case (the rule of the unique index). */
    @Query("select count(s) > 0 from SkillRow s where lower(s.name) = lower(:name)")
    boolean nameTaken(String name);
}
