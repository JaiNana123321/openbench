package edu.cwru.openbench.members;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code admin_change}: one recorded change, written in the same transaction as the change.
 * Insert-only (V120 revokes UPDATE and DELETE), so this entity has no setters.
 */
@Entity
@Table(name = "admin_change")
class AdminChangeRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AdminChangeKind kind;

    private Long memberId;

    private Long teamId;

    private String oldValue;

    private String newValue;

    private Long actorId;

    private Instant at;

    private Long createdBy;

    protected AdminChangeRow() {}

    AdminChangeRow(AdminChangeKind kind, Long memberId, Long teamId, String oldValue, String newValue, Long actorId,
            Instant at) {
        this.kind = kind;
        this.memberId = memberId;
        this.teamId = teamId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.actorId = actorId;
        this.at = at;
        this.createdBy = actorId;
    }

    Long id() {
        return id;
    }

    AdminChangeKind kind() {
        return kind;
    }

    Long memberId() {
        return memberId;
    }

    Long teamId() {
        return teamId;
    }

    String oldValue() {
        return oldValue;
    }

    String newValue() {
        return newValue;
    }

    Long actorId() {
        return actorId;
    }

    Instant at() {
        return at;
    }
}
