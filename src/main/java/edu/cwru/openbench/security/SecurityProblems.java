package edu.cwru.openbench.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cwru.openbench.common.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/**
 * The problem responses of the security filters, which run before Spring MVC and its advice:
 * 401 {@code UNAUTHENTICATED}, 403 {@code CSRF_REJECTED}, and 403 {@code FORBIDDEN}.
 */
@Component
class SecurityProblems implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    SecurityProblems(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        ProblemDetail problem = e instanceof CsrfException
                ? Problems.of(HttpStatus.FORBIDDEN, "CSRF_REJECTED",
                        "The request's CSRF token is missing or wrong. Reload the page and try again.")
                : Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have a role this action needs.");
        write(request, response, problem);
    }

    void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem) throws IOException {
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
