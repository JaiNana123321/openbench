package edu.cwru.openbench.members;

import edu.cwru.openbench.common.SkillId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A row of {@code skill}: one entry of the Administrator's skill list. Other modules see {@link Skill}. */
@Entity
@Table(name = "skill")
class SkillRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Set when an Administrator retires the skill: it stays where it is held but is no longer offered. */
    private Instant retiredAt;

    private Long createdBy;

    protected SkillRow() {}

    SkillRow(String name, Long createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    Long id() {
        return id;
    }

    boolean retired() {
        return retiredAt != null;
    }

    /** Retiring a retired skill keeps its first retirement time. */
    void retire(Instant at) {
        if (retiredAt == null) {
            retiredAt = at;
        }
    }

    Skill toSkill() {
        return new Skill(new SkillId(id), name, retired());
    }
}
