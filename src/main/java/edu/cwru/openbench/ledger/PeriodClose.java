package edu.cwru.openbench.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A row of {@code period_close}: the granted credits a Team's Account lost when a Period closed.
 * The rollover that writes it is not in Demo 1; the Statement and the Account fold read it already.
 */
@Entity
@IdClass(PeriodClose.Key.class)
class PeriodClose {

    @Id
    private Long teamId;

    @Id
    private Long periodId;

    private BigDecimal expired;

    protected PeriodClose() {}

    Long periodId() {
        return periodId;
    }

    BigDecimal expired() {
        return expired;
    }

    record Key(Long teamId, Long periodId) implements Serializable {}
}
