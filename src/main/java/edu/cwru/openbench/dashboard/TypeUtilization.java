package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.inventory.AllocationClass;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Utilization of one Exclusive or Metered type over a Period (SRS Dashboard.Utilization): allocated
 * instance-hours (Exclusive) or unit-hours (Metered) over available hours, where available is
 * {@code capacity} x 24 x the Period's days. Hours are rounded half-up to 2 decimals and the
 * percentage half-up to 2 decimals of a percent.
 *
 * <p>The Period to date runs from the Period's start to {@code toDate}: now, by the Clock, held
 * within the Period, so it is the whole Period once the Period has ended and empty before it begins.
 * {@code idleHoursToDate} is capacity x the hours to date (24 for each whole day, plus the wall-clock
 * hours of today) minus the hours allocated in that span. JSON: {@code {"type": 3, "name": "GPU",
 * "allocationClass": "METERED", "period": 1, "capacity": 16, "availableHours": 11520.00,
 * "allocatedHours": 8.00, "idleHours": 11512.00, "utilizationPercent": 0.07,
 * "toDate": "2026-11-01T13:00:00Z", "idleHoursToDate": 128.00}}.
 */
public record TypeUtilization(
        ResourceTypeId type,
        String name,
        AllocationClass allocationClass,
        PeriodId period,
        int capacity,
        BigDecimal availableHours,
        BigDecimal allocatedHours,
        BigDecimal idleHours,
        BigDecimal utilizationPercent,
        Instant toDate,
        BigDecimal idleHoursToDate) {}
