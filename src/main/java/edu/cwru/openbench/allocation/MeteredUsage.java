package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.MeteredAllocationId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.members.TeamSummary;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One ALLOCATED Metered allocation as the Resource Owner records its usage (SRS
 * Metered.Usage.Record): its Team, Request, type, units, and window; the requested unit-hours, units
 * times the window's hours, rounded half-up to 2 decimals; and, once recorded, the units used, when,
 * and by whom ({@code usedUnits}, {@code recordedAt}, and {@code recordedBy} null until then).
 * {@code recordable} is whether the Period containing the allocation has ended, so usage may be
 * recorded. JSON: {@code {"id": 7, "team": {"id", "name"}, "request": 42, "type": 3, "typeName":
 * "GPU", "units": 4, "window": {"start", "end"}, "requestedUnitHours": 8.00, "usedUnits": 3,
 * "recordedAt": ..., "recordedBy": "Dave Kim", "recordable": true}}.
 */
public record MeteredUsage(
        MeteredAllocationId id,
        TeamSummary team,
        RequestId request,
        ResourceTypeId type,
        String typeName,
        int units,
        TimeWindow window,
        BigDecimal requestedUnitHours,
        Integer usedUnits,
        Instant recordedAt,
        String recordedBy,
        boolean recordable) {}
