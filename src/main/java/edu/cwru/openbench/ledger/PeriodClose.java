package edu.cwru.openbench.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A row of {@code period_close}: the granted credits a Team's Account lost when a Period closed, and
 * its granted and earned credits at the close. Written once per Team and Period by the rollover; its
 * key makes a second rollover of the same boundary a no-op.
 */
@Entity
@IdClass(PeriodClose.Key.class)
class PeriodClose {

    @Id
    private Long teamId;

    @Id
    private Long periodId;

    private BigDecimal expired;

    private BigDecimal closingGranted;

    private BigDecimal closingEarned;

    private Instant closedAt;

    protected PeriodClose() {}

    PeriodClose(Long teamId, Long periodId, BigDecimal expired, BigDecimal closingGranted, BigDecimal closingEarned,
            Instant closedAt) {
        this.teamId = teamId;
        this.periodId = periodId;
        this.expired = expired;
        this.closingGranted = closingGranted;
        this.closingEarned = closingEarned;
        this.closedAt = closedAt;
    }

    Long periodId() {
        return periodId;
    }

    BigDecimal expired() {
        return expired;
    }

    record Key(Long teamId, Long periodId) implements Serializable {}
}
