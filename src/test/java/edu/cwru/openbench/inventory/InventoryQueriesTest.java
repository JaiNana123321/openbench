package edu.cwru.openbench.inventory;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.Role;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The unscoped reads other modules use. They run here without a signed-in Member, as they will from
 * a scheduled job, which shows they carry no role check.
 */
class InventoryQueriesTest extends PostgresTest {

    @Autowired
    private InventoryQueries inventory;

    private long gpus;
    private String nodeA;
    private String nodeB;

    @BeforeEach
    void createAPool() {
        long owner = testData().member(testData().team(unique("Operations")), unique("dave"), Role.RESOURCE_OWNER);
        gpus = testData().resourceType(unique("GPU"), "METERED");
        long model = testData().attribute(gpus, "model", "LIST", "A100", "H100");
        nodeA = unique("node-a");
        nodeB = unique("node-b");
        testData().attributeValue(testData().resource(gpus, nodeA, owner, 8), model, "H100");
        testData().attributeValue(testData().resource(gpus, nodeB, owner, 8), model, "A100");
        long retired = testData().resource(gpus, unique("node-c"), owner, 32);
        jdbc.update("UPDATE resource SET retired_at = now() WHERE id = ?", retired);
    }

    @Test
    void sumsThePoolCapacityOverUnretiredResources() {
        assertThat(inventory.poolCapacity(new ResourceTypeId(gpus))).isEqualTo(16);
        assertThat(inventory.type(new ResourceTypeId(gpus)).poolCapacity()).isEqualTo(16);
    }

    @Test
    void neverOffersARetiredInstance() {
        assertThat(inventory.instancesOf(new ResourceTypeId(gpus)))
                .extracting(Resource::name).containsExactly(nodeA, nodeB);
    }

    @Test
    void searchesByAttributeValue() {
        assertThat(inventory.search(new ResourceFilter(new ResourceTypeId(gpus), Map.of("model", "A100"), false)))
                .extracting(Resource::name, Resource::attributes)
                .containsExactly(tuple(nodeB, Map.of("model", "A100")));
        assertThat(inventory.search(ResourceFilter.ofType(new ResourceTypeId(gpus)))).hasSize(2);
        assertThat(inventory.search(new ResourceFilter(new ResourceTypeId(gpus), Map.of(), true))).hasSize(3);
    }

    @Test
    void reportsAnUnknownTypeAsNotFound() {
        assertThatThrownBy(() -> inventory.type(new ResourceTypeId(Long.MAX_VALUE))).isInstanceOf(NotFoundException.class);
    }
}
