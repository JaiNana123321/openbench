package edu.cwru.openbench.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface GrantPlanRepository extends JpaRepository<GrantPlanRow, GrantPlanRow.Key> {

    /** Each Team's Grant for a Period starting on the day: its plan for the latest Period starting no later. */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT ON (g.team_id) g.team_id AS teamId, g.amount AS amount
            FROM grant_plan g JOIN period p ON p.id = g.period_id
            WHERE p.starts_on <= :startsOn
            ORDER BY g.team_id, p.starts_on DESC""")
    List<PlannedGrant> findPlannedAsOf(LocalDate startsOn);

    interface PlannedGrant {

        Long getTeamId();

        BigDecimal getAmount();
    }
}
