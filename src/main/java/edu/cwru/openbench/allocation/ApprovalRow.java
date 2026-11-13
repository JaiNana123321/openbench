package edu.cwru.openbench.allocation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A row of {@code approval}: one Candidate's approval, addressed to the Manager of their Team. */
@Entity
@Table(name = "approval")
class ApprovalRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long candidateId;

    private Long approverMemberId;

    @Enumerated(EnumType.STRING)
    private ApprovalState state;

    private Long decidedBy;

    private Instant decidedAt;

    private String reason;

    private boolean escalated;

    protected ApprovalRow() {}

    /** A new Outstanding approval. */
    ApprovalRow(Long candidateId, Long approverMemberId) {
        this.candidateId = candidateId;
        this.approverMemberId = approverMemberId;
        this.state = ApprovalState.OUTSTANDING;
    }

    Long id() {
        return id;
    }

    Long candidateId() {
        return candidateId;
    }

    Long approverMemberId() {
        return approverMemberId;
    }

    ApprovalState state() {
        return state;
    }

    Long decidedBy() {
        return decidedBy;
    }

    Instant decidedAt() {
        return decidedAt;
    }

    String reason() {
        return reason;
    }

    boolean escalated() {
        return escalated;
    }

    /** Records an approval or refusal; the caller checks that the approval is Outstanding. */
    void decide(Decision decision, Long actor, Instant at, String reason) {
        this.state = decision == Decision.APPROVE ? ApprovalState.APPROVED : ApprovalState.REFUSED;
        this.decidedBy = actor;
        this.decidedAt = at;
        this.reason = reason;
    }

    /** The system refuses an Approved approval whose Candidate is no longer eligible; no Member decided it. */
    void refuseBySystem(Instant at, String reason) {
        this.state = ApprovalState.REFUSED;
        this.decidedBy = null;
        this.decidedAt = at;
        this.reason = reason;
    }

    void cancel() {
        this.state = ApprovalState.CANCELED;
    }

    void escalate() {
        this.escalated = true;
    }
}
