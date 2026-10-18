package edu.cwru.openbench.inventory;

/**
 * The policy a Resource Type declares, deciding how Request Lines for it are allocated. JSON: the
 * upper-case name, such as {@code "EXCLUSIVE"}.
 */
public enum AllocationClass {
    /** Resources are Members, matched by skill and availability; the type has no Resource rows. */
    MATCHED,
    /** Each Resource is one instance (units 1), held by one line at a time. */
    EXCLUSIVE,
    /** Resources supply units to the type's Pool Capacity, shared by the hour. */
    METERED
}
