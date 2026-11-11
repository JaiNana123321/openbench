package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.AllocatedPart;
import edu.cwru.openbench.allocation.ApprovalState;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.members.TeamSummary;
import java.time.Instant;
import java.util.List;

/**
 * One line of a {@link RequestDetail}: the line with its state and locked Quote, the Resources an
 * allocated Exclusive line is on (empty otherwise, and for a Metered line), and, for a line that
 * waits or was Dropped, why: {@code reason} is a stable code ({@code NO_FREE_INSTANCE},
 * {@code POOL_FULL}, {@code QUOTA_EXCEEDED}, {@code INSUFFICIENT_BALANCE}) and {@code reasonMessage}
 * one sentence for the Manager. {@code waitlistPosition} counts from 1 while the line is on its
 * type's waitlist and is null otherwise. {@code allocatedParts} is what an Exclusive or Metered line
 * holds now, one part per occurrence, after any partial Release; empty when nothing is allocated.
 * {@code candidates} are a Person line's selected Candidates with their approvals, in the order
 * selected; empty on other lines.
 */
public record LineDetail(
        RequestLine line,
        List<AssignedResource> assignedResources,
        String reason,
        String reasonMessage,
        Integer waitlistPosition,
        List<AllocatedPart> allocatedParts,
        List<CandidateApproval> candidates) {

    public LineDetail {
        assignedResources = List.copyOf(assignedResources);
        allocatedParts = List.copyOf(allocatedParts);
        candidates = List.copyOf(candidates);
    }

    /** A Resource a line is allocated on. JSON: {@code {"id": 21, "name": "Room A"}}. */
    public record AssignedResource(ResourceId id, String name) {}

    /**
     * A Candidate selected for a Person line and the state of their approval. {@code decidedByName},
     * {@code decidedAt}, and {@code reason} are null until the lending Manager decides.
     */
    public record CandidateApproval(
            ApprovalId approval,
            MemberId member,
            String name,
            TeamSummary team,
            ApprovalState state,
            boolean escalated,
            String decidedByName,
            Instant decidedAt,
            String reason) {}
}
