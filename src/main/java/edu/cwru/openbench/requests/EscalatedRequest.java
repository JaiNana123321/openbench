package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.members.TeamSummary;
import java.time.Instant;
import java.util.List;

/**
 * A Request whose Objection Window lapsed with approvals outstanding, as the Capacity Planner sees it
 * on the Escalations screen (GET /api/escalations; SRS Matched.Escalate): the requesting Team, the
 * Request's state, when the window lapsed (the client shows the time since), and each escalated
 * Outstanding approval.
 */
public record EscalatedRequest(
        RequestId id,
        TeamSummary team,
        RequestState state,
        Instant lapsedAt,
        List<EscalatedApproval> approvals) {

    public EscalatedRequest {
        approvals = List.copyOf(approvals);
    }

    /**
     * One escalated approval: the Person line and whether it is Required, the Candidate and their
     * Team, the silent lending Manager it was addressed to, the days, and the Earning the lending
     * Team would get (the Day Rate per Monday-to-Friday day).
     */
    public record EscalatedApproval(
            ApprovalId id,
            RequestLineId lineId,
            boolean required,
            MemberId candidate,
            String candidateName,
            TeamSummary lendingTeam,
            MemberId silentManager,
            String silentManagerName,
            DateRange days,
            Credits earning) {}
}
