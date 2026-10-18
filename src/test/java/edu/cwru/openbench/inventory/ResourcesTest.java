package edu.cwru.openbench.inventory;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.common.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Inventory.Resource.Add and Inventory.Pool.Units: Resource Owners add and keep their Resources. */
class ResourcesTest extends InventoryTest {

    private long rooms;
    private String roomsName;
    private long gpus;

    @BeforeEach
    void createTypes() {
        roomsName = unique("Meeting room");
        rooms = testData().resourceType(roomsName, "EXCLUSIVE");
        testData().attribute(rooms, "seats", "NUMBER");
        testData().attribute(rooms, "wing", "LIST", "North", "South");
        testData().attribute(rooms, "opened", "DATE");
        gpus = testData().resourceType(unique("GPU"), "METERED");
        testData().attribute(gpus, "model", "LIST", "A100", "H100");
    }

    private long add(String json) throws Exception {
        String body = postAs(owner, "/api/inventory/resources", json)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    @DisplayName("An Exclusive Resource has units 1 and the caller as its owner")
    void addsAnExclusiveResource() throws Exception {
        String name = unique("Room A");
        postAs(owner, "/api/inventory/resources", """
                {"typeId": %d, "name": "%s", "attributes": {"wing": "North", "seats": " 8 ", "opened": "2024-09-01"}}"""
                .formatted(rooms, name))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.typeId").value(rooms))
                .andExpect(jsonPath("$.typeName").value(roomsName))
                .andExpect(jsonPath("$.ownerId").value(ownerId))
                .andExpect(jsonPath("$.ownerName").value(owner))
                .andExpect(jsonPath("$.units").value(1))
                .andExpect(jsonPath("$.retired").value(false))
                // Values come back in the type's attribute order, trimmed.
                .andExpect(jsonPath("$.attributes.seats").value("8"))
                .andExpect(jsonPath("$.attributes.wing").value("North"))
                .andExpect(jsonPath("$.attributes.opened").value("2024-09-01"));
    }

    @Test
    @DisplayName("Pool Capacity is the sum of the units of the unretired Resources")
    void sumsThePoolCapacity() throws Exception {
        add("""
                {"typeId": %d, "name": "%s", "units": 8, "attributes": {"model": "H100"}}""".formatted(gpus, unique("node-a")));
        long b = add("""
                {"typeId": %d, "name": "%s", "units": 6}""".formatted(gpus, unique("node-b")));
        getAs(member, "/api/inventory/types/" + gpus).andExpect(jsonPath("$.poolCapacity").value(14));

        putAs(owner, "/api/inventory/resources/" + b + "/units", """
                {"units": 8}""").andExpect(status().isOk()).andExpect(jsonPath("$.units").value(8));
        getAs(member, "/api/inventory/types/" + gpus).andExpect(jsonPath("$.poolCapacity").value(16));

        putAs(owner, "/api/inventory/resources/" + b + "/units", """
                {"units": 2}""").andExpect(status().isOk());
        getAs(member, "/api/inventory/types/" + gpus).andExpect(jsonPath("$.poolCapacity").value(10));

        postAs(owner, "/api/inventory/resources/" + b + "/retire")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));
        getAs(member, "/api/inventory/types/" + gpus).andExpect(jsonPath("$.poolCapacity").value(8));
    }

    @Test
    void refusesResourcesForAMatchedType() throws Exception {
        long person = jdbc.queryForObject("SELECT id FROM resource_type WHERE name = 'Person'", Long.class);
        postAs(owner, "/api/inventory/resources", """
                {"typeId": %d, "name": "%s"}""".formatted(person, unique("Ben")))
                .andExpect(refused(422, "MATCHED_HAS_NO_RESOURCES"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "EXCLUSIVE | 2",
        "EXCLUSIVE | 0",
        "METERED   | 0",
        "METERED   | -3",
        "METERED   | null"})
    void refusesUnitsTheClassDoesNotAllow(String allocationClass, String units) throws Exception {
        long type = allocationClass.equals("EXCLUSIVE") ? rooms : gpus;
        postAs(owner, "/api/inventory/resources", """
                {"typeId": %d, "name": "%s", "units": %s}""".formatted(type, unique("thing"), units))
                .andExpect(refused(422, "UNITS_INVALID"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "seats  | many",
        "seats  | 8 seats",
        "opened | tomorrow",
        "opened | 2024-13-01",
        "wing   | West",
        "wing   | north",
        "colour | red"})
    void refusesAValueThatDoesNotFitItsAttribute(String attribute, String value) throws Exception {
        postAs(owner, "/api/inventory/resources", """
                {"typeId": %d, "name": "%s", "attributes": {"%s": "%s"}}""".formatted(rooms, unique("Room"), attribute, value))
                .andExpect(refused(422, "ATTRIBUTE_INVALID"));
    }

    @Test
    @DisplayName("A Resource's name is unique within its type")
    void refusesANameTakenWithinTheType() throws Exception {
        String name = unique("Room A");
        add("""
                {"typeId": %d, "name": "%s"}""".formatted(rooms, name));
        postAs(otherOwner, "/api/inventory/resources", """
                {"typeId": %d, "name": "%s"}""".formatted(rooms, name.toLowerCase()))
                .andExpect(refused(422, "RESOURCE_NAME_TAKEN"));
        // The same name in another type is fine.
        add("""
                {"typeId": %d, "name": "%s", "units": 1}""".formatted(gpus, name));
    }

    @Test
    void refusesAnUnknownTypeAndABlankName() throws Exception {
        postAs(owner, "/api/inventory/resources", """
                {"typeId": %d, "name": "Room"}""".formatted(Long.MAX_VALUE))
                .andExpect(refused(404, "NOT_FOUND"));
        postAs(owner, "/api/inventory/resources", """
                {"typeId": %d, "name": " "}""".formatted(rooms))
                .andExpect(refused(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")));
    }

    @Test
    void letsTheOwnerRenameAndReplaceValues() throws Exception {
        long room = add("""
                {"typeId": %d, "name": "%s", "attributes": {"seats": "8", "wing": "North"}}""".formatted(rooms, unique("Room A")));
        String renamed = unique("Room A1");
        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s", "attributes": {"seats": "10", "opened": "2025-01-15", "wing": ""}}""".formatted(renamed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(renamed))
                .andExpect(jsonPath("$.attributes.seats").value("10"))
                .andExpect(jsonPath("$.attributes.opened").value("2025-01-15"))
                .andExpect(jsonPath("$.attributes.wing").doesNotExist());
        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s", "attributes": {"seats": "ten"}}""".formatted(renamed))
                .andExpect(refused(422, "ATTRIBUTE_INVALID"));
    }

    @Test
    @DisplayName("Only the owner changes a Resource; another Resource Owner gets 403")
    void refusesChangesByAnotherResourceOwner() throws Exception {
        long node = add("""
                {"typeId": %d, "name": "%s", "units": 4}""".formatted(gpus, unique("node")));
        putAs(otherOwner, "/api/inventory/resources/" + node, """
                {"name": "mine now"}""").andExpect(refused(403, "FORBIDDEN"));
        putAs(otherOwner, "/api/inventory/resources/" + node + "/units", """
                {"units": 1}""").andExpect(refused(403, "FORBIDDEN"));
        postAs(otherOwner, "/api/inventory/resources/" + node + "/retire").andExpect(refused(403, "FORBIDDEN"));
        assertThat(jdbc.queryForMap("SELECT units, retired_at FROM resource WHERE id = ?", node))
                .containsEntry("units", 4).containsEntry("retired_at", null);
    }

    @Test
    void handsTheResourceToAnotherActiveResourceOwner() throws Exception {
        long room = add("""
                {"typeId": %d, "name": "%s"}""".formatted(rooms, unique("Room C")));
        String name = jdbc.queryForObject("SELECT name FROM resource WHERE id = ?", String.class, room);

        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s", "ownerId": %d}""".formatted(name, memberId))
                .andExpect(refused(422, "NOT_A_RESOURCE_OWNER"));
        long departed = testData().member(team, unique("olga"), Role.RESOURCE_OWNER);
        testData().deactivate(departed);
        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s", "ownerId": %d}""".formatted(name, departed))
                .andExpect(refused(422, "NOT_A_RESOURCE_OWNER"));
        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s", "ownerId": %d}""".formatted(name, Long.MAX_VALUE))
                .andExpect(refused(422, "NOT_A_RESOURCE_OWNER"));

        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s", "ownerId": %d}""".formatted(name, otherOwnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(otherOwnerId))
                .andExpect(jsonPath("$.ownerName").value(otherOwner));
        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s"}""".formatted(name)).andExpect(refused(403, "FORBIDDEN"));
    }

    @Test
    void refusesUnitsOnAnExclusiveResourceAndBelowOne() throws Exception {
        long room = add("""
                {"typeId": %d, "name": "%s"}""".formatted(rooms, unique("Room D")));
        putAs(owner, "/api/inventory/resources/" + room + "/units", """
                {"units": 2}""").andExpect(refused(422, "NOT_METERED"));
        long node = add("""
                {"typeId": %d, "name": "%s", "units": 4}""".formatted(gpus, unique("node")));
        putAs(owner, "/api/inventory/resources/" + node + "/units", """
                {"units": 0}""").andExpect(refused(422, "UNITS_INVALID"));
        putAs(owner, "/api/inventory/resources/" + node + "/units", """
                {}""").andExpect(refused(400, "VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("A retired Resource is never offered again and cannot change")
    void retiresAResource() throws Exception {
        String name = unique("Room E");
        long room = add("""
                {"typeId": %d, "name": "%s"}""".formatted(rooms, name));
        postAs(owner, "/api/inventory/resources/" + room + "/retire")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));

        getAs(member, "/api/inventory/resources?type=" + rooms).andExpect(jsonPath("$.content[*].name", not(hasItem(name))));
        getAs(member, "/api/inventory/resources?includeRetired=true&type=" + rooms)
                .andExpect(jsonPath("$.content[*].name", hasItem(name)));
        getAs(member, "/api/inventory/resources/" + room).andExpect(jsonPath("$.retired").value(true));

        postAs(owner, "/api/inventory/resources/" + room + "/retire").andExpect(refused(409, "WRONG_STATE"));
        putAs(owner, "/api/inventory/resources/" + room, """
                {"name": "%s"}""".formatted(name)).andExpect(refused(409, "WRONG_STATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE id = ?", Integer.class, room)).isOne();
    }

    @Test
    @DisplayName("Inventory.View.Filter: list Resources by type and attribute value, a page at a time")
    void listsResourcesByTypeAndAttribute() throws Exception {
        String north = unique("Room N");
        String south = unique("Room S");
        add("""
                {"typeId": %d, "name": "%s", "attributes": {"wing": "North", "seats": "8"}}""".formatted(rooms, north));
        add("""
                {"typeId": %d, "name": "%s", "attributes": {"wing": "South", "seats": "8"}}""".formatted(rooms, south));
        add("""
                {"typeId": %d, "name": "%s", "units": 8}""".formatted(gpus, unique("node")));

        getAs(member, "/api/inventory/resources?type=" + rooms)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains(north, south)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(2));
        getAs(member, "/api/inventory/resources?type=" + rooms + "&attr.wing=South&attr.seats=8")
                .andExpect(jsonPath("$.content[*].name", contains(south)));
        getAs(member, "/api/inventory/resources?type=" + rooms + "&size=1&page=1")
                .andExpect(jsonPath("$.content[*].name", contains(south)))
                .andExpect(jsonPath("$.totalElements").value(2));
        getAs(member, "/api/inventory/resources?size=500").andExpect(refused(400, "VALIDATION_FAILED"));
    }
}
