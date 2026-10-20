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

    protected QuotaRow() {}

    Long teamId() {
        return teamId;
    }

    Long typeId() {
        return typeId;
    }

    int maxUnits() {
        return maxUnits;
    }

    record Key(Long teamId, Long typeId) implements Serializable {}
}
