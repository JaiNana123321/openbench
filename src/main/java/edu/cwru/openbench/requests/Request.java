package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.members.TeamSummary;
import java.time.Instant;

/**
 * A Request as other modules and the API see it, without its lines. {@code submittedAt} is null
 * until submission. JSON: {@code {"id": 42, "team": {"id": 1, "name": "Platform"}, "state": "DRAFT", ...}}.
 */
public record Request(RequestId id, TeamSummary team, RequestState state, Instant createdAt, Instant submittedAt) {}
