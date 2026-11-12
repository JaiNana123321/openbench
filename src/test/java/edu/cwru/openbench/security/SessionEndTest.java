package edu.cwru.openbench.security;

import static edu.cwru.openbench.TestData.unique;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.BrowserSession;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.TestData;
import edu.cwru.openbench.common.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * An Administrator's change to a Member ends the Member's open sessions (design Section 7,
 * "Authentication and Sessions"): the next call of such a session answers 401 UNAUTHENTICATED, and a
 * new sign-in carries the new roles and Team. A refused change ends nothing.
 */
@DisplayName("Admin.Member.Create, SE-roles: an Administrator's change ends the Member's sessions")
class SessionEndTest extends PostgresTest {

    private String admin;
    private long platform;
    private String alice;
    private long aliceId;
    private String ben;
    private long benId;

    @BeforeEach
    void createATeam() {
        long office = testData().team(unique("Office"));
        admin = unique("carol");
        testData().member(office, admin, Role.ADMIN);
        platform = testData().team(unique("Platform"));
        alice = unique("alice");
        aliceId = testData().member(platform, alice, Role.MANAGER);
        ben = unique("ben");
        benId = testData().member(platform, ben);
    }

    @Test
    void grantingARoleEndsTheSessionAndTheNextSignInCarriesIt() throws Exception {
        BrowserSession session = BrowserSession.signIn(mvc, ben, TestData.PASSWORD);
        BrowserSession bystander = BrowserSession.signIn(mvc, alice, TestData.PASSWORD);

        grant(benId, "RESOURCE_OWNER").andExpect(status().isOk());

        assertEnded(session);
        mvc.perform(get("/api/me").with(bystander)).andExpect(status().isOk());
        mvc.perform(get("/api/me").with(BrowserSession.signIn(mvc, ben, TestData.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER", "RESOURCE_OWNER")));
    }

    @Test
    void grantingManagerEndsTheSessionsOfTheNewAndThePreviousManager() throws Exception {
        BrowserSession previous = BrowserSession.signIn(mvc, alice, TestData.PASSWORD);
        BrowserSession next = BrowserSession.signIn(mvc, ben, TestData.PASSWORD);

        grant(benId, "MANAGER").andExpect(status().isOk());

        assertEnded(previous);
        assertEnded(next);
        mvc.perform(get("/api/me").with(BrowserSession.signIn(mvc, alice, TestData.PASSWORD)))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("MEMBER")));
    }

    @Test
    void revokingARoleEndsTheSession() throws Exception {
        String dave = unique("dave");
        long daveId = testData().member(platform, dave, Role.RESOURCE_OWNER);
        BrowserSession session = BrowserSession.signIn(mvc, dave, TestData.PASSWORD);

        mvc.perform(delete("/api/admin/roles/{member}/RESOURCE_OWNER", daveId).with(signedInAs(admin)).with(xsrf()))
                .andExpect(status().isOk());

        assertEnded(session);
    }

    @Test
    void deactivatingEndsTheSessionForGood() throws Exception {
        BrowserSession session = BrowserSession.signIn(mvc, ben, TestData.PASSWORD);

        mvc.perform(post("/api/admin/members/{id}/deactivate", benId).with(signedInAs(admin)).with(xsrf()))
                .andExpect(status().isOk());

        assertEnded(session);
        BrowserSession.attemptSignIn(mvc, ben, TestData.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    @DisplayName("Moving a Member ends their sessions: a session's Team decides what the Member sees")
    void movingEndsTheSessionAndTheNextSignInCarriesTheNewTeam() throws Exception {
        long research = testData().team(unique("Research"));
        BrowserSession session = BrowserSession.signIn(mvc, ben, TestData.PASSWORD);

        mvc.perform(post("/api/admin/members/{id}/move", benId).with(signedInAs(admin)).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\": " + research + "}"))
                .andExpect(status().isOk());

        assertEnded(session);
        mvc.perform(get("/api/me").with(BrowserSession.signIn(mvc, ben, TestData.PASSWORD)))
                .andExpect(jsonPath("$.team.id").value((int) research));
    }

    @Test
    void aRefusedChangeEndsNoSession() throws Exception {
        BrowserSession session = BrowserSession.signIn(mvc, alice, TestData.PASSWORD);

        mvc.perform(delete("/api/admin/roles/{member}/MANAGER", aliceId).with(signedInAs(admin)).with(xsrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/admin/members/{id}/deactivate", aliceId).with(signedInAs(admin)).with(xsrf()))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/me").with(session)).andExpect(status().isOk());
    }

    private ResultActions grant(long member, String role) throws Exception {
        return mvc.perform(post("/api/admin/roles").with(signedInAs(admin)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\": " + member + ", \"role\": \"" + role + "\"}"));
    }

    /** The session's next call answers 401 UNAUTHENTICATED, and so does the one after. */
    private void assertEnded(BrowserSession session) throws Exception {
        for (int call = 0; call < 2; call++) {
            mvc.perform(get("/api/me").with(session))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }
}
