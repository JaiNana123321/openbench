package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;

/** A row of {@code grant_plan}: a Team's planned Grant for a Period, which recurs until a later row. */
@Entity
@Table(name = "grant_plan")
@IdClass(GrantPlanRow.Key.class)
class GrantPlanRow {

    @Id
    private Long teamId;

    @Id
    private Long periodId;

    private BigDecimal amount;

    protected GrantPlanRow() {}

    record Key(Long teamId, Long periodId) implements Serializable {}
}
