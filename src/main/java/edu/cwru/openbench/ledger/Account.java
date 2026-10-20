package edu.cwru.openbench.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;

/**
 * A row of {@code account}: the running totals of a Team's Transactions. Loaded only under its row
 * lock ({@link AccountRepository#lockByTeamId}), so the persistence context never holds a row read
 * before the lock; plain reads use {@link AccountRepository#figuresOf}.
 */
@Entity
class Account {

    @Id
    private Long teamId;

    private BigDecimal granted;

    private BigDecimal earned;

    private BigDecimal held;

    protected Account() {}

    BigDecimal available() {
        return granted.add(earned).subtract(held);
    }

    void hold(BigDecimal amount) {
        held = held.add(amount);
    }

    void release(BigDecimal amount) {
        held = held.subtract(amount);
    }

    void grant(BigDecimal amount) {
        granted = granted.add(amount);
    }

    /** Takes a Charge from granted credits first, then earned; returns the part taken from granted. */
    BigDecimal draw(BigDecimal amount) {
        BigDecimal fromGranted = granted.min(amount).max(BigDecimal.ZERO);
        granted = granted.subtract(fromGranted);
        earned = earned.subtract(amount.subtract(fromGranted));
        return fromGranted;
    }
}
