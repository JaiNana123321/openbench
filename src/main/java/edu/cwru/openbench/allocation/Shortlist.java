package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.members.TeamSummary;
import java.util.List;

/**
 * The Candidates for a Matched line, best first (requirement Matched.Shortlist.Build): fewest
 * person-days lent in the current Period, then name. The system never selects from it; the
 * requesting Manager does. A row shows only what a Manager needs to choose (test case
 * {@code mat-shortlist-build}, step 3): name, Team, skills, and person-days lent.
 */
public record Shortlist(RequestLineId lineId, List<Entry> candidates) {

    public Shortlist {
        candidates = List.copyOf(candidates);
    }

    /** One Candidate. {@code position} counts from 1; {@code skills} are skill names, by name. */
    public record Entry(int position, MemberId member, String name, TeamSummary team, List<String> skills,
            int personDaysLent) {

        public Entry {
            skills = List.copyOf(skills);
        }
    }
}
