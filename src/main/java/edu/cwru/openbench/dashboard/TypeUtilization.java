package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.inventory.AllocationClass;
import java.math.BigDecimal;

/**
 * Utilization of one Exclusive or Metered type over a Period (SRS Dashboard.Utilization): allocated
 * instance-hours (Exclusive) or unit-hours (Metered) over available hours, where available is
 * {@code capacity} x 24 x the Period's days. Hours are rounded half-up to 2 decimals and the
 * percentage half-up to 2 decimals of a percent. JSON: {@code {"type": 3, "name": "GPU",
 * "allocationClass": "METERED", "period": 1, "capacity": 16, "availableHours": 11520.00,
 * "allocatedHours": 8.00, "idleHours": 11512.00, "utilizationPercent": 0.07}}.
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
        BigDecimal utilizationPercent) {}
