package edu.cwru.openbench.members;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.TeamId;
import java.time.Instant;

/**
 * One entry of the Administrator's change record (GET /api/admin/changes): what changed, the Member
 * changed (null for a Team change) and the Team, the value before and after as readable text (null
 * where there was none), the Administrator who changed it, and when. Names are today's; the values
 * keep the names of the time. JSON: {@code {"id": 4, "kind": "ROLE_GRANTED", "member": 12,
 * "memberName": "Ben Ortiz", "team": 5, "teamName": "Product Design", "oldValue": "MEMBER",
 * "newValue": "MEMBER, MANAGER", "actor": 6, "actorName": "Carol Davis",
 * "at": "2026-11-12T09:00:00-05:00"}}.
 */
public record AdminChange(
        long id, AdminChangeKind kind, MemberId member, String memberName, TeamId team, String teamName,
        String oldValue, String newValue, MemberId actor, String actorName, Instant at) {}
