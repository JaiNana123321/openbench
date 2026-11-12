package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.TeamId;
import java.time.Instant;

/**
 * The approval of one Candidate for a Matched line, addressed to the Manager of the Candidate's
 * Team. {@code position} is the Candidate's place on the Shortlist when selected (0 for a named
 * Person). {@code decidedBy}, {@code decidedAt}, and {@code reason} are null until the decision;
 * {@code escalated} is set when the Objection Window lapsed with the approval Outstanding, and stays.
 */
public record Approval(
        ApprovalId id,
        RequestLineId lineId,
        RequestId requestId,
        TeamId requestingTeam,
        MemberId candidate,
        int position,
        MemberId approver,
        ApprovalState state,
        boolean escalated,
        MemberId decidedBy,
        Instant decidedAt,
        String reason) {}
