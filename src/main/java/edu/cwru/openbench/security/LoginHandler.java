package edu.cwru.openbench.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cwru.openbench.common.Problems;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.SignedInMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Answers POST /api/login with JSON instead of a redirect, and logs every attempt with username,
 * remote address, and outcome (never the password). A failure says nothing about whether the
 * username exists or the Member is deactivated.
 */
@Component
class LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);

    private final MemberService members;
    private final SecurityProblems problems;
    private final ObjectMapper json;

    LoginHandler(MemberService members, SecurityProblems problems, ObjectMapper json) {
        this.members = members;
        this.problems = problems;
        this.json = json;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        logAttempt(request, authentication.getName(), "SUCCESS");
        SignedInMember member = (SignedInMember) authentication.getPrincipal();
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), MeResponse.of(members.me(member.actor())));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException e) throws IOException {
        logAttempt(request, request.getParameter("username"), outcome(e));
        problems.write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS",
                "The username or password is wrong."));
    }

    private static String outcome(AuthenticationException e) {
        if (e instanceof BadCredentialsException) {
            return "BAD_CREDENTIALS";
        }
        return e instanceof DisabledException ? "DISABLED" : e.getClass().getSimpleName();
    }

    private static void logAttempt(HttpServletRequest request, String username, String outcome) {
        log.info("Sign-in attempt: username={} remoteAddress={} outcome={}",
                printable(username), request.getRemoteAddr(), outcome);
    }

    /** The username as typed, cut short and without control characters, so it cannot forge log lines. */
    private static String printable(String username) {
        if (username == null) {
            return "";
        }
        String clean = username.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > 64 ? clean.substring(0, 64) + "..." : clean;
    }
}
