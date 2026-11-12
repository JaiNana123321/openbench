package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** A row of {@code quota}: the most units of one Metered type a Team may hold concurrently. */
@Entity
@Table(name = "quota")
@IdClass(QuotaRow.Key.class)
class QuotaRow {

    @Id
    private Long teamId;

    @Id
    private Long typeId;

    private int maxUnits;

    private Long createdBy;

    protected QuotaRow() {}

    QuotaRow(Long teamId, Long typeId, int maxUnits, Long createdBy) {
        this.teamId = teamId;
        this.typeId = typeId;
        this.maxUnits = maxUnits;
        this.createdBy = createdBy;
    }

    Long teamId() {
        return teamId;
    }

    Long typeId() {
        return typeId;
    }

    int maxUnits() {
        return maxUnits;
    }

    void changeMaxUnits(int maxUnits) {
        this.maxUnits = maxUnits;
    }

    record Key(Long teamId, Long typeId) implements Serializable {}
}
