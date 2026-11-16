package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.common.WeeklyRecurrence;
import edu.cwru.openbench.inventory.AllocationClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

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

    /** The weekly occurrences of an Exclusive line, the first included; null for a single occurrence. */
    private Integer recurrence;

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

    /** Locks the Quote at submission; null for a type without pricing settings. */
    void lockQuote(BigDecimal quote) {
        this.lockedQuote = quote;
    }

    void setState(RequestLineState state) {
        this.state = state;
    }

    /**
     * Gives {@code amountForNewLine} of this line to a new Optional line of the same type, window, and
     * named Resource, and keeps the rest. The caller checks that both parts are at least 1.
     */
    RequestLineRow split(int amountForNewLine, Instant at, Long actor) {
        change(amount() - amountForNewLine, startsAt, endsAt, namedResourceId);
        RequestLineRow part = new RequestLineRow(requestId, typeId, allocationClass, amountForNewLine, startsAt, endsAt,
                namedResourceId, false, at, actor);
        part.recurrence = recurrence;
        part.priority = priority;
        part.skills = skills;
        part.tierId = tierId;
        return part;
    }

    /** The line's weekly recurrence, or null for a line with one occurrence. */
    WeeklyRecurrence recurrence() {
        return recurrence == null ? null : new WeeklyRecurrence(recurrence);
    }

    /** Replaces the weekly recurrence; null for a single occurrence. */
    void setRecurrence(WeeklyRecurrence recurrence) {
        this.recurrence = recurrence == null ? null : recurrence.occurrences();
    }

    /** Every occurrence of the line, by start, in the company time zone: the window alone without a recurrence. */
    List<TimeWindow> occurrences(ZoneId zone) {
        return WeeklyRecurrence.occurrencesOf(new TimeWindow(startsAt, endsAt), recurrence(), zone);
    }

    void setPriority(boolean priority) {
        this.priority = priority;
    }

    // ---------------------------------------------------------------- Person lines

    /** The skill ids a skill-based Person line asks for; null on any other line. */
    @Column(columnDefinition = "bigint[]")
    private Long[] skills;

    /** A new Draft Person line: one named Person (quantity 1), or a set of skills. */
    static RequestLineRow person(Long requestId, Long typeId, int quantity, Instant startsAt, Instant endsAt,
            Long namedMemberId, Set<Long> skills, Instant createdAt, Long createdBy) {
        RequestLineRow row = new RequestLineRow(requestId, typeId, AllocationClass.MATCHED, quantity, startsAt, endsAt,
                null, true, createdAt, createdBy);
        row.changePerson(quantity, startsAt, endsAt, namedMemberId, skills);
        return row;
    }

    /** Replaces what a Person line asks for. */
    void changePerson(int quantity, Instant startsAt, Instant endsAt, Long namedMemberId, Set<Long> skills) {
        change(quantity, startsAt, endsAt, null);
        this.namedMemberId = namedMemberId;
        this.skills = namedMemberId == null ? skills.stream().sorted().toArray(Long[]::new) : null;
    }

    Long namedMemberId() {
        return namedMemberId;
    }

    /** The skill ids of a skill-based Person line; empty on any other line. */
    Set<Long> skills() {
        return skills == null ? Set.of() : Set.of(skills);
    }

    // ---------------------------------------------------------------- Bumps

    /** Whether a Priority line Bumped this line, which puts it at the head of its waitlist. */
    private boolean bumped;

    boolean bumped() {
        return bumped;
    }

    /** Marks the line Bumped by a Priority line; the mark stays. */
    void markBumped() {
        this.bumped = true;
    }

    /** A new Draft loses its locked Quote, its locked tier premium, and its old Bumped waitlist precedence. */
    void unlockQuote() {
        lockedQuote = null;
        tierPremium = null;
        bumped = false;
    }

    // ---------------------------------------------------------------- service tiers

    /** The service tier of a Metered line; null for none, and on any other line. */
    private Long tierId;

    /** The tier's premium locked at submission with the Quote: the rank the line Bumps with. */
    private BigDecimal tierPremium;

    Long tierId() {
        return tierId;
    }

    void setTier(Long tierId) {
        this.tierId = tierId;
    }

    /** The tier's premium locked at submission; null in a Draft and for a line without a tier. */
    BigDecimal tierPremium() {
        return tierPremium;
    }

    void lockTierPremium(BigDecimal premium) {
        this.tierPremium = premium;
    }

}
