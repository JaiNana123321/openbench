package edu.cwru.openbench.security;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.BrowserSession;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.TestData;
import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.common.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The test case adm-roles-server, step by step, with Ben's real session and CSRF token. */
class RolesServerTest extends PostgresTest {

    @Test
    @DisplayName("adm-roles-server: roles are enforced by the server, not only by the client")
    @Verifies("adm-roles-server")
    void refusesEveryWriteBenHasNoRoleFor() throws Exception {
        long platform = testData().team(unique("Platform"));
        testData().member(platform, unique("alice"), Role.MANAGER);
        String ben = unique("ben");
        testData().member(platform, ben);
        String typeName = unique("Room");
        long type = testData().resourceType(unique("Microscope"), "EXCLUSIVE");
        long requestsBefore = count("SELECT count(*) FROM request WHERE team_id = ?", platform);
        long grantsBefore = count("SELECT count(*) FROM transaction WHERE team_id = ?", platform);

        // Preconditions: a cookie jar with Ben's session and CSRF token.
        BrowserSession session = BrowserSession.signIn(mvc, ben, TestData.PASSWORD);

        // 1. Define a Resource Type: FORBIDDEN; no type is added.
        String typeBody = "{\"name\": \"" + typeName + "\", \"allocationClass\": \"EXCLUSIVE\"}";
        forbidden(post("/api/inventory/types").with(session).contentType(MediaType.APPLICATION_JSON).content(typeBody));
        assertThat(count("SELECT count(*) FROM resource_type WHERE name = ?", typeName)).isZero();

        // 2. Add a Resource: FORBIDDEN.
        forbidden(post("/api/inventory/resources").with(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeId\": " + type + ", \"name\": \"" + unique("Scope") + "\", \"units\": 1}"));
        assertThat(count("SELECT count(*) FROM resource WHERE type_id = ?", type)).isZero();

        // 3. Create a Request: FORBIDDEN.
        forbidden(post("/api/requests").with(session));
        assertThat(count("SELECT count(*) FROM request WHERE team_id = ?", platform)).isEqualTo(requestsBefore);

        // 4. Post a Grant: FORBIDDEN.
        forbidden(post("/api/ledger/grants").with(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": " + platform + ", \"amount\": 200.00, \"reason\": \"More credits\"}"));
        assertThat(count("SELECT count(*) FROM transaction WHERE team_id = ?", platform)).isEqualTo(grantsBefore);

        // 5. Step 1 without the CSRF header: CSRF_REJECTED.
        mvc.perform(post("/api/inventory/types").with(session.withoutCsrfHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(count("SELECT count(*) FROM resource_type WHERE name = ?", typeName)).isZero();

        // 6. Sign out, then /api/me: UNAUTHENTICATED.
        mvc.perform(get("/api/me").with(session)).andExpect(status().isOk());
        mvc.perform(post("/api/logout").with(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").with(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private ResultActions forbidden(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private long count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }
}
