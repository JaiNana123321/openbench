package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.inventory.AllocationClass;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Idle capacity of one Exclusive or Metered type over the coming days (SRS Dashboard.Utilization):
 * available hours ({@code capacity} x 24 x {@code days}) minus the hours allocated in the window
 * from {@code from} (now, by the Clock) to {@code to}. Hours are rounded half-up to 2 decimals.
 */
public record TypeIdle(
        ResourceTypeId type,
        String name,
        AllocationClass allocationClass,
        int days,
        Instant from,
        Instant to,
        int capacity,
        BigDecimal availableHours,
        BigDecimal allocatedHours,
        BigDecimal idleHours) {}
