package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.ApprovalState;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.members.TeamSummary;
import java.time.Instant;

/**
 * An approval as its lending Manager sees it (GET /api/approvals, POST /api/approvals/{id}/decision):
 * the Candidate, the requesting Team and Request, the days, the Earning the lending Team would get
 * (the Day Rate per Monday-to-Friday day), and the end of the Objection Window, by which the
 * Manager decides; the client shows the time left. {@code decidedByName}, {@code decidedAt}, and
 * {@code reason} are null until the decision.
 */
public record ApprovalView(
        ApprovalId id,
        RequestId requestId,
        RequestLineId lineId,
        MemberId candidate,
        String candidateName,
        TeamSummary requestingTeam,
        DateRange days,
        Credits earning,
        Instant windowEndsAt,
        ApprovalState state,
        boolean escalated,
        String decidedByName,
        Instant decidedAt,
        String reason) {}
