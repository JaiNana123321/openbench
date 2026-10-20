package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.members.TeamSummary;
import java.time.Instant;

/**
 * One row of GET /api/requests: the Request, how many lines it has, and the sum of its Required
 * lines' Quotes (current Quotes in a Draft, locked Quotes after submission).
 */
public record RequestSummary(
        RequestId id,
        TeamSummary team,
        RequestState state,
        Instant createdAt,
        Instant submittedAt,
        int lineCount,
        Credits requiredSum) {}
