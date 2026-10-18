package edu.cwru.openbench.inventory;

import static edu.cwru.openbench.TestData.unique;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** SE-roles on the Inventory: every Member reads; only the right role writes, whatever the client. */
class InventorySecurityTest extends InventoryTest {

    private long type;
    private long resource;

    @BeforeEach
    void createAResource() {
        type = testData().resourceType(unique("GPU"), "METERED");
        resource = testData().resource(type, unique("node"), ownerId, 4);
    }

    /** Each write with a valid body, run by someone who lacks its role. */
    @ParameterizedTest(name = "{0} {1} as {2}")
    @CsvSource(delimiter = '|', value = {
        "POST | /api/inventory/types                    | member | {\"name\": \"X\", \"allocationClass\": \"EXCLUSIVE\"}",
        "POST | /api/inventory/types                    | owner  | {\"name\": \"X\", \"allocationClass\": \"EXCLUSIVE\"}",
        "PUT  | /api/inventory/types/{type}             | member | {\"name\": \"X\", \"allocationClass\": \"METERED\"}",
        "POST | /api/inventory/types/{type}/retire      | owner  | ",
        "POST | /api/inventory/resources                | member | {\"typeId\": {type}, \"name\": \"X\", \"units\": 1}",
        "POST | /api/inventory/resources                | admin  | {\"typeId\": {type}, \"name\": \"X\", \"units\": 1}",
        "PUT  | /api/inventory/resources/{resource}       | member | {\"name\": \"X\"}",
        "PUT  | /api/inventory/resources/{resource}/units | admin  | {\"units\": 1}",
        "POST | /api/inventory/resources/{resource}/retire | member | "})
    @DisplayName("SE-roles: a write without its role is refused with 403 FORBIDDEN and changes nothing")
    void refusesAWriteWithoutItsRole(String method, String path, String who, String body) throws Exception {
        String username = switch (who) {
            case "member" -> member;
            case "owner" -> owner;
            default -> admin;
        };
        String uri = path.replace("{type}", String.valueOf(type)).replace("{resource}", String.valueOf(resource));
        MockHttpServletRequestBuilder request = method.equals("POST") ? post(uri) : put(uri);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body.replace("{type}", String.valueOf(type)));
        }
        mvc.perform(request.with(signedInAs(username)).with(xsrf()))
                .andExpect(refused(403, "FORBIDDEN"));

        getAs(member, "/api/inventory/resources/" + resource)
                .andExpect(jsonPath("$.units").value(4))
                .andExpect(jsonPath("$.retired").value(false));
        getAs(member, "/api/inventory/types/" + type)
                .andExpect(jsonPath("$.allocationClass").value("METERED"))
                .andExpect(jsonPath("$.retired").value(false));
    }

    @Test
    void letsEveryMemberRead() throws Exception {
        getAs(member, "/api/inventory/types").andExpect(status().isOk());
        getAs(member, "/api/inventory/types/" + type).andExpect(status().isOk());
        getAs(member, "/api/inventory/resources").andExpect(status().isOk());
        getAs(member, "/api/inventory/resources/" + resource).andExpect(status().isOk());
    }

    @Test
    void refusesAnAnonymousCaller() throws Exception {
        mvc.perform(get("/api/inventory/types")).andExpect(refused(401, "UNAUTHENTICATED"));
    }
}
