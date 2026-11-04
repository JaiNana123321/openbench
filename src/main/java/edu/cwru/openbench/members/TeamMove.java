package edu.cwru.openbench.members;

import edu.cwru.openbench.common.TeamId;
import jakarta.validation.constraints.NotNull;

/** The body of POST /api/admin/members/{id}/move: the Team the Member moves to. */
public record TeamMove(@NotNull TeamId teamId) {}
