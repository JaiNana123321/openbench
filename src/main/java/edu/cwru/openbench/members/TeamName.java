package edu.cwru.openbench.members;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The body of POST /api/admin/teams and PUT /api/admin/teams/{id}: the Team's name. */
public record TeamName(@NotBlank @Size(max = 100) String name) {}
