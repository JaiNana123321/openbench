package edu.cwru.openbench.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;

/**
 * A row of {@code held_credit}: the locked Quote of one line on Hold, so the Ledger knows a line's
 * held amount without reading Requests' tables. Deleted when the Hold commits or releases; read and
 * written only under the Team's account lock.
 */
@Entity
class HeldCredit {

    @Id
    private Long lineId;

    private Long teamId;

    private BigDecimal amount;

    protected HeldCredit() {}

    HeldCredit(Long lineId, Long teamId, BigDecimal amount) {
        this.lineId = lineId;
        this.teamId = teamId;
        this.amount = amount;
    }

    Long teamId() {
        return teamId;
    }

    BigDecimal amount() {
        return amount;
    }
}
