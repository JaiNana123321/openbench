package edu.cwru.openbench.members;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** A row of {@code member_skill}: the Member holds the skill. */
@Entity
@Table(name = "member_skill")
@IdClass(MemberSkillRow.Key.class)
class MemberSkillRow {

    @Id
    private Long memberId;

    @Id
    private Long skillId;

    private Long createdBy;

    protected MemberSkillRow() {}

    MemberSkillRow(Long memberId, Long skillId, Long createdBy) {
        this.memberId = memberId;
        this.skillId = skillId;
        this.createdBy = createdBy;
    }

    Long skillId() {
        return skillId;
    }

    record Key(Long memberId, Long skillId) implements Serializable {}
}
