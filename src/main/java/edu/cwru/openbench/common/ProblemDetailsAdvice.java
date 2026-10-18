package edu.cwru.openbench.common;

import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception that leaves a controller into a problem detail with a {@code code}
 * (design Table "HTTP status per error class"). Spring MVC's own exceptions (malformed JSON, a
 * missing parameter, an unknown path) keep Spring's status and get a code from it.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private static final String EXCLUSION_VIOLATION = "23P01";

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return respond(Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> forbidden(ForbiddenException e) {
        return respond(Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage()));
    }

    /** A missing role, raised by {@code @PreAuthorize}. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException e) {
        return respond(Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have a role this action needs."));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException e) {
        return respond(Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."));
    }

    @ExceptionHandler(RuleViolation.class)
    ResponseEntity<ProblemDetail> ruleViolation(RuleViolation e) {
        HttpStatus status = e.isStateConflict() ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = Problems.of(status, e.code(), e.getMessage());
        e.properties().forEach(problem::setProperty);
        return respond(problem);
    }

    @ExceptionHandler(ConcurrencyRetryException.class)
    ResponseEntity<ProblemDetail> concurrencyRetry(ConcurrencyRetryException e) {
        log.warn("Allocation failed twice on a lock wait or deadlock", e);
        ProblemDetail problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENCY_RETRY",
                "The system is busy with a conflicting change. Try again in a moment.");
        return ResponseEntity.status(problem.getStatus()).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    /** An exclusion-constraint violation means a path skipped the type lock: a bug, logged as one. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> dataIntegrity(DataIntegrityViolationException e) {
        if (!hasSqlState(e, EXCLUSION_VIOLATION)) {
            return internal(e);
        }
        log.error("Exclusion constraint violated; an allocation path skipped the lock or a check", e);
        return respond(Problems.of(HttpStatus.CONFLICT, "ALLOCATION_CONFLICT",
                "The capacity was taken by another change. Nothing was changed; try again."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> internal(Exception e) {
        log.error("Unexpected error", e);
        return respond(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                "An unexpected error occurred. It has been logged."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(status, "VALIDATION_FAILED", "Some fields are invalid.");
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
                .toList());
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    /** Adds a {@code code} to the problem details Spring MVC builds for its own exceptions. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            Problems.withCode(problem, codeFor(status));
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static String codeFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return switch (status.value()) {
            case 400 -> "VALIDATION_FAILED";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 500 -> "INTERNAL";
            default -> known != null ? known.name() : "HTTP_" + status.value();
        };
    }

    private static boolean hasSqlState(Throwable e, String sqlState) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
