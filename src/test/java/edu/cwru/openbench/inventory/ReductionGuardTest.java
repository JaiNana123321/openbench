package edu.cwru.openbench.inventory;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Inventory.Pool.Reduce: a reduction or retirement asks the {@link ReductionGuard} and is refused,
 * naming the earliest failing hour, when the guard finds one. The guard here is a test double that
 * answers an hour, standing in for Allocation's.
 */
class ReductionGuardTest extends InventoryTest {

    /** Thursday 15 October 2026, 14:00 in New York. */
    private static final Instant BUSY_HOUR = Instant.parse("2026-10-15T18:00:00Z");

    @MockitoBean
    private ReductionGuard guard;

    @Autowired
    private Clock clock;

    private long gpus;
    private long node;
    private long rooms;
    private long room;

    @BeforeEach
    void createResources() {
        gpus = testData().resourceType(unique("GPU"), "METERED");
        node = testData().resource(gpus, unique("node-a"), ownerId, 8);
        rooms = testData().resourceType(unique("Room"), "EXCLUSIVE");
        room = testData().resource(rooms, unique("Room A"), ownerId, 1);
    }

    @Test
    @DisplayName("A reduction below future usage is refused with UNITS_BELOW_USAGE and the earliest hour")
    void refusesAReductionBelowUsage() throws Exception {
        when(guard.earliestHourExceeding(any(), any(), eq(4), any())).thenReturn(Optional.of(BUSY_HOUR));
        Instant before = clock.instant();

        putAs(owner, "/api/inventory/resources/" + node + "/units", """
                {"units": 4}""")
                .andExpect(refused(422, "UNITS_BELOW_USAGE"))
                .andExpect(jsonPath("$.earliestHour").value("2026-10-15T14:00:00-04:00"))
                .andExpect(jsonPath("$.detail").value(containsString("Thu 15 Oct 2026, 14:00")));

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(guard).earliestHourExceeding(
                eq(new ResourceTypeId(gpus)), eq(Optional.of(new ResourceId(node))), eq(4), from.capture());
        assertThat(from.getValue()).isBetween(before, clock.instant());
        assertThat(jdbc.queryForObject("SELECT units FROM resource WHERE id = ?", Integer.class, node)).isEqualTo(8);
    }

    @Test
    void reducesWhenTheGuardFindsNoFailingHour() throws Exception {
        putAs(owner, "/api/inventory/resources/" + node + "/units", """
                {"units": 5}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units").value(5));
        verify(guard).earliestHourExceeding(eq(new ResourceTypeId(gpus)), eq(Optional.of(new ResourceId(node))), eq(5), any());
    }

    @Test
    void anIncreaseSkipsTheGuard() throws Exception {
        when(guard.earliestHourExceeding(any(), any(), anyInt(), any())).thenReturn(Optional.of(BUSY_HOUR));
        putAs(owner, "/api/inventory/resources/" + node + "/units", """
                {"units": 12}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units").value(12));
        verifyNoInteractions(guard);
    }

    @Test
    @DisplayName("Retiring an Exclusive Resource in use is refused with RESOURCE_IN_USE and the earliest hour")
    void refusesToRetireAnExclusiveResourceInUse() throws Exception {
        when(guard.earliestHourExceeding(any(), any(), eq(0), any())).thenReturn(Optional.of(BUSY_HOUR));

        postAs(owner, "/api/inventory/resources/" + room + "/retire")
                .andExpect(refused(422, "RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.earliestHour").value("2026-10-15T14:00:00-04:00"));

        verify(guard).earliestHourExceeding(eq(new ResourceTypeId(rooms)), eq(Optional.of(new ResourceId(room))), eq(0), any());
        assertThat(jdbc.queryForObject("SELECT retired_at IS NULL FROM resource WHERE id = ?", Boolean.class, room)).isTrue();
    }

    @Test
    @DisplayName("Retiring a Metered Resource whose units are in use is refused with UNITS_BELOW_USAGE")
    void refusesToRetireAMeteredResourceInUse() throws Exception {
        when(guard.earliestHourExceeding(any(), any(), eq(0), any())).thenReturn(Optional.of(BUSY_HOUR));

        postAs(owner, "/api/inventory/resources/" + node + "/retire")
                .andExpect(refused(422, "UNITS_BELOW_USAGE"))
                .andExpect(jsonPath("$.earliestHour").value("2026-10-15T14:00:00-04:00"));
        assertThat(jdbc.queryForObject("SELECT retired_at IS NULL FROM resource WHERE id = ?", Boolean.class, node)).isTrue();
    }

    @Test
    void retiresWhenTheGuardFindsNoFailingHour() throws Exception {
        postAs(owner, "/api/inventory/resources/" + room + "/retire")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));
        verify(guard).earliestHourExceeding(eq(new ResourceTypeId(rooms)), eq(Optional.of(new ResourceId(room))), eq(0), any());
    }
}
