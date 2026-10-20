package edu.cwru.openbench.allocation;

import edu.cwru.openbench.inventory.AllocationClass;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every {@link Allocator} bean, indexed by its Allocation Class. Start-up fails when two allocators
 * claim one class. The design also fails start-up for a class without one, but Matched has none
 * until its increment, so {@link #forClass} refuses it when called instead.
 */
@Component
class AllocatorIndex implements AllocatorRegistry {

    private final Map<AllocationClass, Allocator> byClass = new EnumMap<>(AllocationClass.class);

    AllocatorIndex(List<Allocator> allocators) {
        for (Allocator allocator : allocators) {
            Allocator previous = byClass.putIfAbsent(allocator.allocationClass(), allocator);
            if (previous != null) {
                throw new IllegalStateException("Two allocators claim " + allocator.allocationClass() + ": "
                        + previous.getClass().getName() + " and " + allocator.getClass().getName() + ".");
            }
        }
    }

    @Override
    public Allocator forClass(AllocationClass allocationClass) {
        Allocator allocator = byClass.get(allocationClass);
        if (allocator == null) {
            throw new IllegalStateException("No allocator handles " + allocationClass + " lines yet.");
        }
        return allocator;
    }

    @Override
    public <T> Optional<T> capability(AllocationClass allocationClass, Class<T> capability) {
        return Optional.ofNullable(byClass.get(allocationClass)).filter(capability::isInstance).map(capability::cast);
    }
}
