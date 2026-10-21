package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.inventory.AllocationClass;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("ADR-0007: AllocatorRegistry")
class AllocatorRegistryTest extends AllocationTest {

    @Test
    @DisplayName("finds the Exclusive and Metered allocators and their Waitlistable capability")
    void findsEachClassAndItsCapabilities() {
        assertThat(registry.forClass(AllocationClass.EXCLUSIVE).allocationClass()).isEqualTo(AllocationClass.EXCLUSIVE);
        assertThat(registry.forClass(AllocationClass.METERED).allocationClass()).isEqualTo(AllocationClass.METERED);
        assertThat(registry.capability(AllocationClass.EXCLUSIVE, Waitlistable.class)).isPresent();
        assertThat(registry.capability(AllocationClass.METERED, Waitlistable.class)).isPresent();
    }

    @Test
    @DisplayName("refuses Matched, which has no allocator in this increment, with a clear error")
    void refusesMatched() {
        assertThatThrownBy(() -> registry.forClass(AllocationClass.MATCHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATCHED");
        assertThat(registry.capability(AllocationClass.MATCHED, Waitlistable.class)).isEmpty();
    }

    @Test
    @DisplayName("fails at start-up when two allocators claim one class")
    void failsOnTwoAllocatorsForOneClass() {
        Allocator one = Mockito.mock(Allocator.class);
        Allocator two = Mockito.mock(Allocator.class);
        Mockito.when(one.allocationClass()).thenReturn(AllocationClass.EXCLUSIVE);
        Mockito.when(two.allocationClass()).thenReturn(AllocationClass.EXCLUSIVE);

        assertThatThrownBy(() -> new AllocatorIndex(List.of(one, two)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two allocators claim EXCLUSIVE");
    }
}
