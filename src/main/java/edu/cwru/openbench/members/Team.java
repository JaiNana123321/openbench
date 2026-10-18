package edu.cwru.openbench.members;

import edu.cwru.openbench.common.TeamId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/** A row of {@code team}. Package-private: other modules see {@link TeamSummary}. */
@Entity
class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Long managerMemberId;

    /** Set when an Administrator retires the Team; read by {@link TeamRepository}'s queries. */
    private Instant retiredAt;

    protected Team() {}

    Long id() {
        return id;
    }

    /** The Team's Manager, or null for a Team that never had one. */
    Long managerMemberId() {
        return managerMemberId;
    }

    TeamSummary toSummary() {
        return new TeamSummary(new TeamId(id), name);
    }
}
