package edu.cwru.openbench.members;

import edu.cwru.openbench.common.SkillId;

/**
 * One entry of the Administrator's skill list. A retired skill stays where it is held but is no
 * longer offered. JSON: {@code {"id": 3, "name": "Statistics", "retired": false}}.
 */
public record Skill(SkillId id, String name, boolean retired) {}
