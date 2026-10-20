package edu.cwru.openbench.allocation;

import edu.cwru.openbench.inventory.AllocationClass;
import java.util.Optional;

/** The allocator of each Allocation Class and its capabilities, so Requests never names a class. */
public interface AllocatorRegistry {

    /**
     * The class's allocator.
     *
     * @throws IllegalStateException for a class without one (MATCHED, until its increment)
     */
    Allocator forClass(AllocationClass allocationClass);

    /**
     * The class's allocator as {@code capability}, such as {@link Waitlistable}, if it has that
     * capability; empty otherwise, also for a class without an allocator.
     */
    <T> Optional<T> capability(AllocationClass allocationClass, Class<T> capability);
}
