package edu.cwru.openbench.security;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.TestData;
import edu.cwru.openbench.common.Role;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Sign-in, sign-out, and /api/me through MockMvc with a real session and CSRF token. */
@DisplayName("CO-auth, SE-password: session login with CSRF")
class SessionTest extends PostgresTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private String teamName;
    private long team;
    private String username;
    private long member;

    @BeforeEach
    void createAManager() {
        teamName = unique("Platform");
        team = testData().team(teamName);
        username = unique("alice");
        member = testData().member(team, username, Role.MANAGER);
    }

    @Test
    void signsInReadsMeAndSignsOut() throws Exception {
        MvcResult signedOut = mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();
        Cookie token = csrfCookie(signedOut);
        assertThat(token.isHttpOnly()).isFalse();

        MvcResult signedIn = signIn(token, username, TestData.PASSWORD)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(me(), JsonCompareMode.STRICT))
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(content().json(me(), JsonCompareMode.STRICT));

        Cookie rotated = csrfCookie(signedIn);
        mvc.perform(post("/api/logout").session(session).cookie(rotated).header(CSRF_HEADER, rotated.getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refusesAWrongPasswordAnUnknownUsernameAndADeactivatedMemberAlike() throws Exception {
        Cookie token = csrfCookie(mvc.perform(get("/api/me")).andReturn());
        String deactivated = unique("ben");
        testData().deactivate(testData().member(team, deactivated));

        for (String[] attempt : new String[][] {
                {username, "wrong"}, {unique("nobody"), TestData.PASSWORD}, {deactivated, TestData.PASSWORD}}) {
            signIn(token, attempt[0], attempt[1])
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("The username or password is wrong."))
                    .andExpect(jsonPath("$.instance").value("/api/login"));
        }
    }

    @Test
    void refusesASignInWithoutTheCsrfHeader() throws Exception {
        Cookie token = csrfCookie(mvc.perform(get("/api/me")).andReturn());

        mvc.perform(post("/api/login").cookie(token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", TestData.PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/login").cookie(token).header(CSRF_HEADER, "forged")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", TestData.PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    }

    @Test
    void answersAnUnauthenticatedApiCallWith401AndNoRedirect() throws Exception {
        mvc.perform(get("/api/teams"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/teams"));
    }

    @Test
    void letsTestsSignInWithoutTheLoginRoundTrip() throws Exception {
        mvc.perform(get("/api/me").with(signedInAs(username)))
                .andExpect(status().isOk())
                .andExpect(content().json(me(), JsonCompareMode.STRICT));
        mvc.perform(post("/api/logout").with(signedInAs(username)).with(xsrf()))
                .andExpect(status().isNoContent());
    }

    private ResultActions signIn(Cookie token, String username, String password) throws Exception {
        return mvc.perform(post("/api/login").cookie(token).header(CSRF_HEADER, token.getValue())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username)
                .param("password", password));
    }

    /** The exact /api/me body the web client codes against. */
    private String me() {
        return """
                {"id": %d, "name": "%s", "username": "%s", "team": {"id": %d, "name": "%s"}, "roles": ["MANAGER", "MEMBER"]}"""
                .formatted(member, username, username, team, teamName);
    }

    /** The last XSRF-TOKEN cookie a response set (a sign-in clears the old token and sets a new one). */
    private static Cookie csrfCookie(MvcResult result) {
        Cookie cookie = Arrays.stream(result.getResponse().getCookies())
                .filter(c -> c.getName().equals(CSRF_COOKIE))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("The response set no " + CSRF_COOKIE + " cookie"));
        assertThat(cookie.getValue()).isNotBlank();
        return cookie;
    }
}
