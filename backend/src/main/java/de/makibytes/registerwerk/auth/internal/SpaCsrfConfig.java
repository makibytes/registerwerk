package de.makibytes.registerwerk.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Spring Security's documented "SPA" CSRF integration (reference docs, "Integrating with CSRF
 * Protection" → SPA) — needed because the session moved from a body-returned bearer token to an
 * httpOnly cookie: without CSRF protection, any other site the browser has open could ride that
 * cookie into a mutating request. The {@code XSRF-TOKEN} cookie / {@code X-XSRF-TOKEN} header
 * names match Angular {@code HttpClient}'s {@code withXsrfConfiguration} defaults exactly, so
 * both frontends need no extra wiring beyond enabling it.
 *
 * <p>Spring Security 6's default {@code CsrfTokenRequestHandler} defers generating the token
 * until something reads it, so the {@code XSRF-TOKEN} cookie would never be written for a plain
 * page load — only once something happened to trigger it. {@link CsrfCookieFilter} forces that
 * read on every request so the cookie is always present after the very first response.
 */
public final class SpaCsrfConfig {

    private SpaCsrfConfig() {}

    /**
     * A request carrying the {@code X-XSRF-TOKEN} header (both frontends, via Angular's
     * `withXsrfConfiguration`) resolves the raw value straight from that header — the value
     * came from reading the (non-httpOnly) cookie, so no further decoding is needed. Everything
     * else (a server-rendered form's hidden {@code _csrf} parameter — not used by either
     * frontend today, but matching Spring's own documented handler keeps the default BREACH
     * protection for that path) falls through to the normal XOR-masked handler.
     */
    public static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
        private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            this.delegate.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
                return super.resolveCsrfTokenValue(request, csrfToken);
            }
            return this.delegate.resolveCsrfTokenValue(request, csrfToken);
        }
    }

    public static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken(); // triggers lazy resolution -> writes the XSRF-TOKEN cookie
            }
            filterChain.doFilter(request, response);
        }
    }
}
