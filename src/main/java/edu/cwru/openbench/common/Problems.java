package edu.cwru.openbench.common;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 9457 problem details of the API: {@code type} {@code /problems/<code>},
 * {@code title} the status's reason phrase, {@code detail} one sentence for the user, and the
 * extension field {@code code}. Spring fills {@code instance} with the request path.
 */
public final class Problems {

    private Problems() {}

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        return withCode(ProblemDetail.forStatusAndDetail(status, detail), code);
    }

    /** Sets {@code code} and the matching {@code type} on a problem built elsewhere. */
    public static ProblemDetail withCode(ProblemDetail problem, String code) {
        problem.setType(URI.create("/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
