package edu.cwru.openbench.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A row of {@code transaction}, append-only: the application role may insert and read but never
 * update or delete one (V7). Amounts are positive; {@code grantedPart} and {@code earnedPart} say
 * which part of the Account a Grant, Charge, or Refund moved, and sum to {@code amount}.
 */
@Entity
@Table(name = "transaction")
class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long teamId;

    @Enumerated(EnumType.STRING)
    private TransactionKind kind;

    private BigDecimal amount;

    private BigDecimal grantedPart;

    private BigDecimal earnedPart;

    private Long periodId;

    /** The Request Line it concerns, or null. */
    private Long lineId;

    /** The mid-Period Grant's reason, or null. */
    private String reason;

    /** The Member who caused it, or null for the system. */
    private Long actorId;

    private Instant postedAt;

    protected Transaction() {}

    private Transaction(Long teamId, TransactionKind kind, BigDecimal grantedPart, BigDecimal earnedPart,
            Long periodId, Long lineId, String reason, Long actorId, Instant postedAt) {
        this.teamId = teamId;
        this.kind = kind;
        this.amount = grantedPart.add(earnedPart);
        this.grantedPart = grantedPart;
        this.earnedPart = earnedPart;
        this.periodId = periodId;
        this.lineId = lineId;
        this.reason = reason;
        this.actorId = actorId;
        this.postedAt = postedAt;
    }

    static Transaction grant(Long teamId, BigDecimal amount, Long periodId, String reason, Long actorId, Instant postedAt) {
        return new Transaction(teamId, TransactionKind.GRANT, amount, BigDecimal.ZERO, periodId, null, reason, actorId, postedAt);
    }

    static Transaction charge(Long teamId, BigDecimal grantedPart, BigDecimal earnedPart, Long periodId, Long lineId,
            Long actorId, Instant postedAt) {
        return new Transaction(teamId, TransactionKind.CHARGE, grantedPart, earnedPart, periodId, lineId, null, actorId, postedAt);
    }

    Long id() {
        return id;
    }

    TransactionKind kind() {
        return kind;
    }

    BigDecimal amount() {
        return amount;
    }

    /** The granted part as it changes the Account: negative for a Charge. */
    BigDecimal signedGranted() {
        return grantedPart.multiply(BigDecimal.valueOf(kind.sign()));
    }

    /** The earned part as it changes the Account: negative for a Charge. */
    BigDecimal signedEarned() {
        return earnedPart.multiply(BigDecimal.valueOf(kind.sign()));
    }

    BigDecimal grantedPart() {
        return grantedPart;
    }

    BigDecimal earnedPart() {
        return earnedPart;
    }

    Long periodId() {
        return periodId;
    }

    Long lineId() {
        return lineId;
    }

    String reason() {
        return reason;
    }

    Long actorId() {
        return actorId;
    }

    Instant postedAt() {
        return postedAt;
    }
}
