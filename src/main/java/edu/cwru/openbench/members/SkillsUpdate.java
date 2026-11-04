package edu.cwru.openbench.members;

import edu.cwru.openbench.common.SkillId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The body of PUT /api/members/me/skills: every skill the Member holds, by id from the list. */
public record SkillsUpdate(@NotNull @Size(max = 200) List<@NotNull SkillId> skills) {}
