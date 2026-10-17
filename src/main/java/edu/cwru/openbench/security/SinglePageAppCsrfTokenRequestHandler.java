package edu.cwru.openbench.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * The CSRF handling of a single-page client, as in the Spring Security reference ("Single-Page
 * Applications"); Spring Security 7 ships it as {@code csrf.spa()}.
 */
final class SinglePageAppCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        // A token rendered into a response body stays BREACH-protected.
        xor.handle(request, response, csrfToken);
        // Spring Security defers loading the token; load it now so that every response, a 401
        // included, sets the XSRF-TOKEN cookie when the client has none.
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        // The client copies the raw cookie value into the header; a form parameter carries the
        // masked value of a rendered token.
        boolean fromHeader = StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()));
        return (fromHeader ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
