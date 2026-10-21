package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.TestData;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Steps 2 to 4 of the demo script over HTTP, as the web client makes the calls: each Manager signs
 * in through POST /api/login with the XSRF-TOKEN cookie, and every later call carries the session
 * and the token. The demo world's shape is built in test setup ({@link DemoWorldTest}).
 */
@DisplayName("End to end: submit to Allocated, the Statement's Charges, Waiting with positions, and the shortfall")
class SubmitEndToEndTest extends DemoWorldTest {

    @Test
    void runsTheDemoStory() throws Exception {
        RequestPostProcessor aliceSession = signIn(alice);
        RequestPostProcessor bobSession = signIn(bob);

        // 1. Alice submits Room A and 4 GPU units, and the Request is Allocated.
        long aliceRequest = createDraft(aliceSession);
        long roomLine = line(aliceSession, aliceRequest, room, 1, tuesdayAt(14), tuesdayAt(16), roomA);
        long gpuLine = line(aliceSession, aliceRequest, gpu, 4, tuesdayAt(9), thursdayAt(9), null);
        mvc.perform(get("/api/requests/{id}/draft", aliceRequest).with(aliceSession))
                .andExpect(jsonPath("$.requiredSum").value(1150.00))
                .andExpect(jsonPath("$.availableBalance").value(5000.00));
        submit(aliceSession, aliceRequest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(aliceSession, aliceRequest)
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].assignedResources[0].name").value(roomAName))
                .andExpect(jsonPath("$.lines[1].line.state").value("ALLOCATED"));

        // 2. Platform's Statement shows the Grant and two Charges equal to the locked Quotes, and
        // the Available Balance drops by 1150.00.
        mvc.perform(get("/api/ledger/statements/{team}/{period}", platform, period).with(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.grants").value(5000.00))
                .andExpect(jsonPath("$.totals.charges").value(1150.00))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].amount", containsInAnyOrder(30.00, 1120.00)))
                .andExpect(jsonPath("$.transactions[?(@.kind == 'CHARGE')].line",
                        containsInAnyOrder((int) roomLine, (int) gpuLine)))
                .andExpect(jsonPath("$.account.available").value(3850.00));
        mvc.perform(get("/api/requests/lines").param("ids", roomLine + "," + gpuLine).with(aliceSession))
                .andExpect(jsonPath("$[*].requestId", containsInAnyOrder((int) aliceRequest, (int) aliceRequest)));

        // 3 and 4. Bob asks for Room A at an overlapping time and for 10 GPU units, over Research's
        // Quota of 8: the Request is Waiting, each line at position 1 with its reason.
        long bobRequest = createDraft(bobSession);
        long bobRoom = line(bobSession, bobRequest, room, 1, tuesdayAt(15), tuesdayAt(17), roomA);
        long bobGpu = line(bobSession, bobRequest, gpu, 10, tuesdayAt(10), tuesdayAt(14), null);
        submit(bobSession, bobRequest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"));
        detail(bobSession, bobRequest)
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("NO_FREE_INSTANCE"))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1))
                .andExpect(jsonPath("$.lines[1].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[1].reason").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.lines[1].waitlistPosition").value(1));
        assertThat(available(research)).isEqualByComparingTo("600.00");

        // 5. A Request costing more than Research's Available Balance is refused with the shortfall.
        long tooDear = createDraft(bobSession);
        line(bobSession, tooDear, microscope, 1, tuesdayAt(9), tuesdayAt(17), null);
        line(bobSession, tooDear, room, 1, tuesdayAt(9), tuesdayAt(17), roomB);
        line(bobSession, tooDear, gpu, 1, tuesdayAt(9), tuesdayAt(10), null);
        submit(bobSession, tooDear)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.shortfall").value(7.50))
                .andExpect(jsonPath("$.required").value(607.50))
                .andExpect(jsonPath("$.available").value(600.00));
        detail(bobSession, tooDear).andExpect(jsonPath("$.request.state").value("DRAFT"));

        // 6. Withdrawing the Waiting Request takes its lines off the waitlist.
        withdraw(bobSession, bobRequest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WITHDRAWN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id IN (?, ?)",
                Integer.class, bobRoom, bobGpu)).isZero();
        detail(bobSession, bobRequest).andExpect(jsonPath("$.lines[0].waitlistPosition").isEmpty());

        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
        assertThat(account(research)).isEqualTo(foldOfTransactions(research));
    }

    /**
     * Signs in as the client does: GET /api/me sets the XSRF-TOKEN cookie, POST /api/login sends it
     * back in X-XSRF-TOKEN, and the sign-in sets a fresh token. Returns the session and that token.
     */
    private RequestPostProcessor signIn(String username) throws Exception {
        Cookie first = csrfCookie(mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn());
        MvcResult signedIn = mvc.perform(post("/api/login").cookie(first).header("X-XSRF-TOKEN", first.getValue())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username)
                        .param("password", TestData.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        Cookie token = csrfCookie(signedIn);
        return request -> {
            request.setSession(session);
            request.setCookies(token);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }

    /** The last XSRF-TOKEN cookie the response set. */
    private static Cookie csrfCookie(MvcResult result) {
        return Arrays.stream(result.getResponse().getCookies())
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN") && !cookie.getValue().isEmpty())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("The response set no XSRF-TOKEN cookie"));
    }
}
