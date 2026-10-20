package edu.cwru.openbench.requests;

import edu.cwru.openbench.inventory.AllocationClass;
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
 * A row of {@code request_line}: one need inside a Request, over the half-open window
 * {@code [startsAt, endsAt)}. An Exclusive line keeps its instances in {@code quantity}; a Metered
 * line keeps the units it holds concurrently in {@code units}, with {@code quantity} 1.
 * {@link #amount()} reads whichever applies. The Allocation Class is copied from the type.
 */
@Entity
@Table(name = "request_line")
class RequestLineRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long requestId;

    private Long typeId;

    @Enumerated(EnumType.STRING)
    private AllocationClass allocationClass;

    private Integer quantity;

    private Integer units;

    private Instant startsAt;

    private Instant endsAt;

    private boolean required;

    private boolean priority;

    private Long namedResourceId;

    private Long namedMemberId;

    /** The Quote locked at submission; null while the Request is a Draft. */
    private BigDecimal lockedQuote;

    @Enumerated(EnumType.STRING)
    private RequestLineState state;

    private Instant createdAt;

    private Long createdBy;

    protected RequestLineRow() {}

    /** A new Draft line. */
    RequestLineRow(Long requestId, Long typeId, AllocationClass allocationClass, int amount, Instant startsAt,
            Instant endsAt, Long namedResourceId, boolean required, Instant createdAt, Long createdBy) {
        this.requestId = requestId;
        this.typeId = typeId;
        this.allocationClass = allocationClass;
        this.state = RequestLineState.DRAFT;
        this.required = required;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        change(amount, startsAt, endsAt, namedResourceId);
    }

    Long id() {
        return id;
    }

    Long requestId() {
        return requestId;
    }

    Long typeId() {
        return typeId;
    }

    AllocationClass allocationClass() {
        return allocationClass;
    }

    /** The instances of an Exclusive line, or the units of a Metered line. */
    int amount() {
        return allocationClass == AllocationClass.METERED ? units : quantity;
    }

    Instant startsAt() {
        return startsAt;
    }

    Instant endsAt() {
        return endsAt;
    }

    boolean required() {
        return required;
    }

    boolean priority() {
        return priority;
    }

    Long namedResourceId() {
        return namedResourceId;
    }

    /** The Quote locked at submission, or null while the line is in a Draft. */
    BigDecimal lockedQuote() {
        return lockedQuote;
    }

    RequestLineState state() {
        return state;
    }

    /** Replaces what the line asks for; the type, and so the Allocation Class, never changes. */
    void change(int amount, Instant startsAt, Instant endsAt, Long namedResourceId) {
        boolean metered = allocationClass == AllocationClass.METERED;
        this.quantity = metered ? 1 : amount;
        this.units = metered ? amount : null;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.namedResourceId = namedResourceId;
    }

    void setRequired(boolean required) {
        this.required = required;
    }

    /**
     * Gives {@code amountForNewLine} of this line to a new Optional line of the same type, window, and
     * named Resource, and keeps the rest. The caller checks that both parts are at least 1.
     */
    RequestLineRow split(int amountForNewLine, Instant at, Long actor) {
        change(amount() - amountForNewLine, startsAt, endsAt, namedResourceId);
        return new RequestLineRow(requestId, typeId, allocationClass, amountForNewLine, startsAt, endsAt,
                namedResourceId, false, at, actor);
    }
}
