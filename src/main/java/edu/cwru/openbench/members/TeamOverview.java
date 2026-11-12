package edu.cwru.openbench.members;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.TeamId;

/**
 * A Team as the Administrator's Teams screen shows it (GET /api/admin/teams): its name, whether it
 * is retired, its Manager (null while it has none), and how many active Members it has.
 */
public record TeamOverview(
        TeamId id, String name, boolean retired, MemberId manager, String managerName, long activeMembers) {}
