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

    private Long createdBy;

    protected Team() {}

    /** A new Team, without Members or a Manager. */
    Team(String name, Long createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    Long id() {
        return id;
    }

    String name() {
        return name;
    }

    void rename(String name) {
        this.name = name;
    }

    /** Retiring a retired Team keeps its first retirement time. */
    void retire(Instant at) {
        if (retiredAt == null) {
            retiredAt = at;
        }
    }

    /** The Member, already in this Team and holding the role in {@code member_role}, is its Manager. */
    void appointManager(Long memberId) {
        this.managerMemberId = memberId;
    }

    /** The Team's Manager, or null for a Team that never had one. */
    Long managerMemberId() {
        return managerMemberId;
    }

    boolean retired() {
        return retiredAt != null;
    }

    /** The Team's Manager left it: it has no Manager until the role is granted again. */
    void clearManager() {
        this.managerMemberId = null;
    }

    TeamSummary toSummary() {
        return new TeamSummary(new TeamId(id), name);
    }
}
