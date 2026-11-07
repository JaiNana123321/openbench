package edu.cwru.openbench.members;

import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import java.util.List;

/**
 * A Member's profile: name, email, Team, skills by name, and availability ranges by first day. It
 * holds no other personal data (SE-data). The Team is read-only here; only an Administrator moves a
 * Member.
 */
public record MemberProfile(
        MemberId id, String name, String email, TeamSummary team, List<Skill> skills, List<DateRange> availability) {

    public MemberProfile {
        skills = List.copyOf(skills);
        availability = List.copyOf(availability);
    }
}
