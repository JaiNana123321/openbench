package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.inventory.AllocationClass;

/** The number of lines on one Exclusive or Metered type's Waitlist now. */
public record TypeWaiting(ResourceTypeId type, String name, AllocationClass allocationClass, int waiting) {}
