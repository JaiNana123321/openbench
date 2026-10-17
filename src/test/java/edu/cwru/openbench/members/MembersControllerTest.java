package edu.cwru.openbench.members;

import static edu.cwru.openbench.TestData.unique;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MembersControllerTest extends PostgresTest {

    @Test
    void listsTheActiveTeamsToAnySignedInMember() throws Exception {
        String name = unique("Operations");
        long team = testData().team(name);
        String username = unique("dave");
        testData().member(team, username);
        String retired = unique("Retired");
        jdbc.update("UPDATE team SET retired_at = now() WHERE id = ?", testData().team(retired));

        mvc.perform(get("/api/teams").with(signedInAs(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '%s')].id", name).value((int) team))
                .andExpect(jsonPath("$[*].name", not(hasItem(retired))));
    }

    @Test
    @DisplayName("SE-roles: a caller without the role a service needs gets 403 FORBIDDEN")
    void refusesACallerWithoutTheRole() throws Exception {
        mvc.perform(get("/api/teams").with(user("outsider").authorities(() -> "SOMETHING_ELSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
