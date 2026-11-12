package edu.cwru.openbench;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A Member's cookie jar after a real sign-in through POST /api/login, as the web client holds it:
 * the server-side session and the CSRF token. Unlike {@code PostgresTest.signedInAs}, which skips the
 * login, the session is registered with Spring Security, so it can be ended by the server.
 *
 * <p>Add it to a request with {@code .with(session)}: it sends the session, the {@code XSRF-TOKEN}
 * cookie, and the {@code X-XSRF-TOKEN} header. {@link #withoutCsrfHeader()} leaves the header out.
 */
public final class BrowserSession implements RequestPostProcessor {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockHttpSession session;
    private final Cookie token;
    private final boolean header;

    private BrowserSession(MockHttpSession session, Cookie token, boolean header) {
        this.session = session;
        this.token = token;
        this.header = header;
    }

    /** Signs in through POST /api/login; fails the test unless the server answers 200. */
    public static BrowserSession signIn(MockMvc mvc, String username, String password) throws Exception {
        MvcResult result = attemptSignIn(mvc, username, password).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("Sign-in of " + username + " answered " + result.getResponse().getStatus() + ": "
                    + result.getResponse().getContentAsString());
        }
        return new BrowserSession((MockHttpSession) result.getRequest().getSession(false), csrfCookie(result), true);
    }

    /** POST /api/login with a fresh CSRF token, as the sign-in page sends it; the response is the test's to check. */
    public static ResultActions attemptSignIn(MockMvc mvc, String username, String password) throws Exception {
        Cookie token = csrfCookie(mvc.perform(get("/api/me")).andReturn());
        return mvc.perform(post("/api/login").cookie(token).header(CSRF_HEADER, token.getValue())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username)
                .param("password", password));
    }

    /** The same session and cookie, without the {@code X-XSRF-TOKEN} header. */
    public BrowserSession withoutCsrfHeader() {
        return new BrowserSession(session, token, false);
    }

    @Override
    public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
        request.setSession(session);
        Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
        Cookie[] cookies = Arrays.copyOf(existing, existing.length + 1);
        cookies[existing.length] = token;
        request.setCookies(cookies);
        if (header) {
            request.addHeader(CSRF_HEADER, token.getValue());
        }
        return request;
    }

    /** The last XSRF-TOKEN cookie a response set (a sign-in clears the old token and sets a new one). */
    private static Cookie csrfCookie(MvcResult result) {
        return Arrays.stream(result.getResponse().getCookies())
                .filter(cookie -> cookie.getName().equals(CSRF_COOKIE) && !cookie.getValue().isEmpty())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("The response set no " + CSRF_COOKIE + " cookie"));
    }
}
