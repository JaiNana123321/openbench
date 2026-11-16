package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;

/**
 * Requested against used units of one Team on one Metered type over a Period (SRS
 * Metered.Usage.Compare, Dashboard.Usage): over the Team's ALLOCATED allocations of the type whose
 * usage has been recorded ({@code recorded} of them), units times hours and used units times hours,
 * each window clipped to the Period and rounded half-up to 2 decimals; {@code unrecorded} counts the
 * allocations still waiting for a value. JSON: {@code {"team": 1, "teamName": "Platform", "type": 3,
 * "typeName": "GPU", "period": 1, "requestedUnitHours": 8.00, "usedUnitHours": 6.00, "recorded": 1,
 * "unrecorded": 0}}.
 */
public record TeamTypeUsage(
        TeamId team,
        String teamName,
        ResourceTypeId type,
        String typeName,
        PeriodId period,
        BigDecimal requestedUnitHours,
        BigDecimal usedUnitHours,
        int recorded,
        int unrecorded) {}
