package edu.cwru.openbench.allocation;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.common.ResourceTypeId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The real {@code ReductionGuard} behind Inventory's reduction and retirement (requirement
 * Inventory.Resource.Add), over HTTP as the Resource Owner, against rows the allocators wrote.
 */
@DisplayName("Inventory.Resource.Add: the reduction guard")
class UsageReductionGuardTest extends AllocationTest {

    @Nested
    @DisplayName("A Metered pool of two 8-unit GPU nodes")
    class GpuNodes {

        private long gpus;
        private long nodeB;

        /** Day 7: 4 units held 09:00 to 17:00. Day 8: 10 units allocated 10:00 to 12:00. Yesterday: 16 units. */
        @BeforeEach
        void allocateSomeUnits() {
            gpus = meteredType();
            node(gpus, 8);
            nodeB = node(gpus, 8);
            hold(meteredLine(team, gpus, 4, window(7, 9, 17)));
            commit(meteredLine(team, gpus, 10, window(8, 10, 12)));
            commit(meteredLine(team, gpus, 16, window(-1, 9, 17)));
        }

        @Test
        @DisplayName("allows a reduction that leaves the peak of future usage")
        void allowsAReductionDownToThePeak() throws Exception {
            setUnits(nodeB, 2).andExpect(status().isOk()).andExpect(jsonPath("$.units").value(2));
        }

        @Test
        @DisplayName("refuses a reduction below the peak, naming the earliest failing hour, not the first used one")
        void refusesAReductionBelowThePeak() throws Exception {
            MvcResult result = setUnits(nodeB, 1)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("UNITS_BELOW_USAGE"))
                    .andReturn();

            assertThat(earliestHour(result)).isEqualTo(at(8, 10));
            assertThat(unitsOf(nodeB)).isEqualTo(8);
        }

        @Test
        @DisplayName("refuses to retire a node when the rest cannot carry the future usage")
        void refusesToRetireANodeInUse() throws Exception {
            MvcResult result = retire(nodeB)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("UNITS_BELOW_USAGE"))
                    .andReturn();

            assertThat(earliestHour(result)).isEqualTo(at(8, 10));
        }

        @Test
        @DisplayName("counts a Hold exactly like an allocation")
        void countsHolds() throws Exception {
            jdbc.update("UPDATE metered_allocation SET state = 'HELD' WHERE type_id = ? AND units = 10", gpus);

            setUnits(nodeB, 1).andExpect(jsonPath("$.code").value("UNITS_BELOW_USAGE"));
        }

        @Test
        @DisplayName("ignores released units, and past usage never counted")
        void ignoresReleasedUnits() throws Exception {
            jdbc.update("UPDATE metered_allocation SET state = 'RELEASED' WHERE type_id = ? AND units = 10", gpus);

            setUnits(nodeB, 1).andExpect(status().isOk());
            assertThat(inTransaction(() -> queries.peakUnitsInUse(new ResourceTypeId(gpus), Optional.empty(),
                    clock.instant()))).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("An Exclusive room")
    class Rooms {

        private long rooms;
        private long room;

        @BeforeEach
        void createARoom() {
            rooms = exclusiveType();
            room = room(rooms, unique("Room"));
        }

        @Test
        @DisplayName("refuses to retire a room with a future allocation, naming its start")
        void refusesToRetireARoomInUse() throws Exception {
            commit(namedLine(team, rooms, window(9, 14, 16), room));
            commit(namedLine(team, rooms, window(7, 9, 10), room));

            MvcResult result = retire(room)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"))
                    .andReturn();

            assertThat(earliestHour(result)).isEqualTo(at(7, 9));
            assertThat(jdbc.queryForObject("SELECT retired_at IS NULL FROM resource WHERE id = ?", Boolean.class, room))
                    .isTrue();
        }

        @Test
        @DisplayName("retires a room whose only use is past or released")
        void retiresARoomNotInUse() throws Exception {
            commit(namedLine(team, rooms, window(-2, 9, 10), room));
            LineSnapshot released = namedLine(team, rooms, window(7, 9, 10), room);
            commit(released);
            release(released);

            retire(room).andExpect(status().isOk()).andExpect(jsonPath("$.retired").value(true));
        }
    }

    private ResultActions setUnits(long resource, int units) throws Exception {
        return mvc.perform(put("/api/inventory/resources/" + resource + "/units")
                .with(signedInAs(ownerUsername)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"units\": " + units + "}"));
    }

    private ResultActions retire(long resource) throws Exception {
        return mvc.perform(post("/api/inventory/resources/" + resource + "/retire")
                .with(signedInAs(ownerUsername)).with(xsrf()));
    }

    private int unitsOf(long resource) {
        return jdbc.queryForObject("SELECT units FROM resource WHERE id = ?", Integer.class, resource);
    }

    private static Instant earliestHour(MvcResult result) throws Exception {
        String hour = JsonPath.read(result.getResponse().getContentAsString(), "$.earliestHour");
        return OffsetDateTime.parse(hour).toInstant();
    }
}
