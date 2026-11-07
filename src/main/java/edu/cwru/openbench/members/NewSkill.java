package edu.cwru.openbench.members;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The body of POST /api/admin/skills: the name of a skill to add to the list. */
public record NewSkill(@NotBlank @Size(max = 100) String name) {}
