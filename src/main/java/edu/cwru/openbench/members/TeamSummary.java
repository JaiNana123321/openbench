package edu.cwru.openbench.members;

import edu.cwru.openbench.common.TeamId;

/** A Team as other modules and the API see it. JSON: {@code {"id": 1, "name": "Platform"}}. */
public record TeamSummary(TeamId id, String name) {}
