package edu.cwru.openbench.members;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.Role;
import jakarta.validation.constraints.NotNull;

/** The body of POST /api/admin/roles: the Member and the role to grant them. */
public record RoleGrant(@NotNull MemberId memberId, @NotNull Role role) {}
