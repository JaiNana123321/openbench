package edu.cwru.openbench.inventory;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Inventory.Type.Define: an Administrator defines, changes, and retires Resource Types as data. */
class ResourceTypesTest extends InventoryTest {

    private long personId() {
        return jdbc.queryForObject("SELECT id FROM resource_type WHERE name = 'Person'", Long.class);
    }

    @Test
    @DisplayName("Inventory.Type.NoCode: a new type is a row, listed at once with its attributes")
    void definesATypeAsData() throws Exception {
        String name = unique("3D printer");
        String body = postAs(admin, "/api/inventory/types", """
                {"name": " %s ", "allocationClass": "EXCLUSIVE",
                 "attributes": [{"name": "material", "kind": "LIST", "allowedValues": ["PLA", "ABS"]},
                                {"name": "bought", "kind": "DATE"}]}""".formatted(name))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.allocationClass").value("EXCLUSIVE"))
                .andExpect(jsonPath("$.retired").value(false))
                .andExpect(jsonPath("$.poolCapacity").value(nullValue()))
                .andExpect(jsonPath("$.attributes[0].name").value("material"))
                .andExpect(jsonPath("$.attributes[0].kind").value("LIST"))
                .andExpect(jsonPath("$.attributes[0].allowedValues[1]").value("ABS"))
                .andExpect(jsonPath("$.attributes[1].kind").value("DATE"))
                .andExpect(jsonPath("$.attributes[1].allowedValues").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(body, "$.id");

        getAs(member, "/api/inventory/types")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '%s')].id", name).value(id));
        getAs(member, "/api/inventory/types/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.length()").value(2));
    }

    @Test
    void answersCreatedWithTheLocationOfTheNewType() throws Exception {
        var response = postAs(admin, "/api/inventory/types", """
                {"name": "%s", "allocationClass": "METERED"}""".formatted(unique("GPU")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poolCapacity").value(0))
                .andExpect(jsonPath("$.attributes").isEmpty())
                .andReturn().getResponse();
        int id = JsonPath.read(response.getContentAsString(), "$.id");
        assertThat(response.getHeader("Location")).isEqualTo("/api/inventory/types/" + id);
    }

    @Test
    void refusesANameAnotherTypeHasIgnoringCase() throws Exception {
        String name = unique("Meeting room");
        testData().resourceType(name, "EXCLUSIVE");

        postAs(admin, "/api/inventory/types", """
                {"name": "%s", "allocationClass": "EXCLUSIVE"}""".formatted(name.toUpperCase()))
                .andExpect(refused(422, "TYPE_NAME_TAKEN"))
                .andExpect(jsonPath("$.instance").value("/api/inventory/types"));
        long other = testData().resourceType(unique("Lab"), "EXCLUSIVE");
        putAs(admin, "/api/inventory/types/" + other, """
                {"name": "%s", "allocationClass": "EXCLUSIVE"}""".formatted(name))
                .andExpect(refused(422, "TYPE_NAME_TAKEN"));
        putAs(admin, "/api/inventory/types/" + personId(), """
                {"name": "Person", "allocationClass": "MATCHED", "attributes": []}""")
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{\"name\": \"wing\", \"kind\": \"LIST\"}]",
        "[{\"name\": \"wing\", \"kind\": \"LIST\", \"allowedValues\": []}]",
        "[{\"name\": \"wing\", \"kind\": \"LIST\", \"allowedValues\": [\"North\", \"North\"]}]",
        "[{\"name\": \"wing\", \"kind\": \"LIST\", \"allowedValues\": [\" \"]}]",
        "[{\"name\": \"seats\", \"kind\": \"NUMBER\", \"allowedValues\": [\"8\"]}]",
        "[{\"name\": \"seats\", \"kind\": \"NUMBER\"}, {\"name\": \"Seats\", \"kind\": \"TEXT\"}]",
        "[{\"name\": \" \", \"kind\": \"TEXT\"}]",
        "[{\"name\": \"seats\"}]"})
    void refusesAMalformedAttribute(String attributes) throws Exception {
        postAs(admin, "/api/inventory/types", """
                {"name": "%s", "allocationClass": "EXCLUSIVE", "attributes": %s}""".formatted(unique("Room"), attributes))
                .andExpect(refused(422, "ATTRIBUTE_INVALID"));
    }

    @Test
    void refusesABlankNameOrMissingClassAsInvalidFields() throws Exception {
        postAs(admin, "/api/inventory/types", """
                {"name": " ", "allocationClass": null}""")
                .andExpect(refused(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("allocationClass")));
    }

    @Test
    void changesTheNameAndAttributesKeepingValuesInUse() throws Exception {
        long type = testData().resourceType(unique("Room"), "EXCLUSIVE");
        long seats = testData().attribute(type, "seats", "NUMBER");
        long wing = testData().attribute(type, "wing", "LIST", "North", "South");
        long floor = testData().attribute(type, "floor", "NUMBER");
        long room = testData().resource(type, unique("Room A"), ownerId, 1);
        testData().attributeValue(room, wing, "North");
        testData().attributeValue(room, seats, "8");

        String name = unique("Meeting room");
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "EXCLUSIVE", "attributes": [
                  {"id": %d, "name": "capacity", "kind": "NUMBER"},
                  {"id": %d, "name": "wing", "kind": "LIST", "allowedValues": ["North", "South", "East"]},
                  {"name": "projector", "kind": "TEXT"}]}""".formatted(name, seats, wing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.attributes[*].name").value(contains("capacity", "wing", "projector")))
                .andExpect(jsonPath("$.attributes[1].allowedValues.length()").value(3));

        // The unused attribute floor is gone, and the Resource keeps its values under the new names.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_type_attribute WHERE id = ?", Integer.class, floor)).isZero();
        getAs(member, "/api/inventory/resources/" + room)
                .andExpect(jsonPath("$.attributes.capacity").value("8"))
                .andExpect(jsonPath("$.attributes.wing").value("North"));
    }

    @Test
    void refusesToRemoveAnAttributeOrValueInUse() throws Exception {
        long type = testData().resourceType(unique("Room"), "EXCLUSIVE");
        long seats = testData().attribute(type, "seats", "TEXT");
        long wing = testData().attribute(type, "wing", "LIST", "North", "South");
        long room = testData().resource(type, unique("Room A"), ownerId, 1);
        testData().attributeValue(room, wing, "North");
        testData().attributeValue(room, seats, "eight");
        String name = jdbc.queryForObject("SELECT name FROM resource_type WHERE id = ?", String.class, type);

        // Removing wing, which Room A uses.
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "EXCLUSIVE", "attributes": [{"id": %d, "name": "seats", "kind": "TEXT"}]}"""
                .formatted(name, seats))
                .andExpect(refused(422, "ATTRIBUTE_IN_USE"));
        // Removing North, which Room A uses.
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "EXCLUSIVE", "attributes": [
                  {"id": %d, "name": "seats", "kind": "TEXT"},
                  {"id": %d, "name": "wing", "kind": "LIST", "allowedValues": ["South"]}]}"""
                .formatted(name, seats, wing))
                .andExpect(refused(422, "ATTRIBUTE_IN_USE"));
        // Making seats a number, which "eight" is not.
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "EXCLUSIVE", "attributes": [
                  {"id": %d, "name": "seats", "kind": "NUMBER"},
                  {"id": %d, "name": "wing", "kind": "LIST", "allowedValues": ["North", "South"]}]}"""
                .formatted(name, seats, wing))
                .andExpect(refused(422, "ATTRIBUTE_IN_USE"));
        // An attribute of another type.
        long other = testData().attribute(testData().resourceType(unique("Lab"), "EXCLUSIVE"), "lab", "TEXT");
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "EXCLUSIVE", "attributes": [
                  {"id": %d, "name": "seats", "kind": "TEXT"},
                  {"id": %d, "name": "wing", "kind": "LIST", "allowedValues": ["North"]},
                  {"id": %d, "name": "lab", "kind": "TEXT"}]}"""
                .formatted(name, seats, wing, other))
                .andExpect(refused(422, "ATTRIBUTE_INVALID"));
        assertThat(jdbc.queryForList("SELECT name FROM resource_type_attribute WHERE type_id = ? ORDER BY id", String.class, type))
                .containsExactly("seats", "wing");
    }

    @Test
    @DisplayName("The Allocation Class is fixed once a Resource of the type exists")
    void fixesTheClassOnceAResourceExists() throws Exception {
        String name = unique("Scope");
        long type = testData().resourceType(name, "EXCLUSIVE");
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "METERED"}""".formatted(name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocationClass").value("METERED"));

        testData().resource(type, unique("scope-1"), ownerId, 2);
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "EXCLUSIVE"}""".formatted(name))
                .andExpect(refused(422, "CLASS_FIXED"));
        assertThat(jdbc.queryForObject("SELECT allocation_class FROM resource_type WHERE id = ?", String.class, type))
                .isEqualTo("METERED");
    }

    @Test
    @DisplayName("Inventory.Type.Person: Person keeps its name and class and is never retired")
    void keepsThePersonTypeFixed() throws Exception {
        long person = personId();
        postAs(admin, "/api/inventory/types/" + person + "/retire").andExpect(refused(422, "PERSON_TYPE_FIXED"));
        putAs(admin, "/api/inventory/types/" + person, """
                {"name": "Person", "allocationClass": "EXCLUSIVE"}""")
                .andExpect(refused(422, "PERSON_TYPE_FIXED"));
        putAs(admin, "/api/inventory/types/" + person, """
                {"name": "People", "allocationClass": "MATCHED"}""")
                .andExpect(refused(422, "PERSON_TYPE_FIXED"));
        assertThat(jdbc.queryForMap("SELECT name, allocation_class, retired_at FROM resource_type WHERE id = ?", person))
                .containsEntry("name", "Person").containsEntry("allocation_class", "MATCHED").containsEntry("retired_at", null);
    }

    @Test
    @DisplayName("Inventory.Type.Retire: a retired type is listed only on request and accepts no new Resources")
    void retiresAType() throws Exception {
        String name = unique("Old room");
        long type = testData().resourceType(name, "EXCLUSIVE");
        long room = testData().resource(type, unique("Room Z"), ownerId, 1);

        postAs(admin, "/api/inventory/types/" + type + "/retire")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));

        getAs(member, "/api/inventory/types").andExpect(jsonPath("$[*].name", not(hasItem(name))));
        getAs(member, "/api/inventory/types?includeRetired=true").andExpect(jsonPath("$[*].name", hasItem(name)));
        // Its Resources are untouched, and nothing is deleted.
        getAs(member, "/api/inventory/resources/" + room).andExpect(jsonPath("$.retired").value(false));

        postAs(admin, "/api/inventory/types/" + type + "/retire").andExpect(refused(409, "WRONG_STATE"));
        putAs(admin, "/api/inventory/types/" + type, """
                {"name": "%s", "allocationClass": "EXCLUSIVE"}""".formatted(name))
                .andExpect(refused(409, "WRONG_STATE"));
        postAs(owner, "/api/inventory/resources", """
                {"typeId": %d, "name": "%s"}""".formatted(type, unique("Room Y")))
                .andExpect(refused(422, "TYPE_RETIRED"));
    }

    @Test
    void answersNotFoundForAnUnknownType() throws Exception {
        getAs(member, "/api/inventory/types/" + Long.MAX_VALUE).andExpect(refused(404, "NOT_FOUND"));
        postAs(admin, "/api/inventory/types/" + Long.MAX_VALUE + "/retire").andExpect(refused(404, "NOT_FOUND"));
    }
}
