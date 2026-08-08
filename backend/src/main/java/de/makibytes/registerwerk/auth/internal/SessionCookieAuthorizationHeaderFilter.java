package de.makibytes.registerwerk.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Synthesizes an {@code Authorization: Bearer <rw_session>} header from the httpOnly session
 * cookie ({@link SessionCookieService}) when a request carries no {@code Authorization} header
 * of its own — this is what lets the cookie transparently authenticate ordinary requests without
 * every call site needing to change, without also making {@code oauth2ResourceServer()}'s
 * {@code bearerTokenResolver} itself cookie-aware.
 *
 * <p>That distinction matters: when a {@code CsrfConfigurer} and {@code oauth2ResourceServer()}
 * are both configured, Spring Security automatically exempts any request that resolves a bearer
 * token — via <em>whichever</em> {@code bearerTokenResolver} is registered — from CSRF
 * protection, on the reasoning that a header a page's own JavaScript had to place there isn't an
 * ambient credential a forged cross-site request could replay. That reasoning stops holding the
 * moment the resolver also reads an ambient cookie: pointing {@code bearerTokenResolver} at a
 * cookie-aware resolver (as an earlier version of this class did, implementing
 * {@code BearerTokenResolver} directly) silently exempted every cookie-authenticated request
 * from the CSRF protection {@link SessionCookieService}'s cookie move was specifically added to
 * require, without touching a single CSRF-related line to do it.
 *
 * <p>Running this filter <em>after</em> {@code CsrfFilter} keeps that automatic exemption
 * correctly scoped to genuine header-bearer requests (step-up/dual-control tokens,
 * service-to-service calls, the impersonation-exchange step) — {@code CsrfFilter} sees the
 * original, unmodified request and finds no header, so cookie-only requests still hit CSRF
 * validation. Running it <em>before</em> {@code BearerTokenAuthenticationFilter} means the
 * resource server still authenticates the request normally afterwards, via its own
 * unmodified default (header-only) {@code bearerTokenResolver}.
 */
public class SessionCookieAuthorizationHeaderFilter extends OncePerRequestFilter {

    private final SessionCookieService cookies;

    public SessionCookieAuthorizationHeaderFilter(SessionCookieService cookies) {
        this.cookies = cookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpServletRequest downstream = request;
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            downstream = cookies.readSessionCookie(request)
                    .<HttpServletRequest>map(token -> new WrappedRequest(request, token))
                    .orElse(request);
        }
        filterChain.doFilter(downstream, response);
    }

    private static final class WrappedRequest extends HttpServletRequestWrapper {
        private final String bearerHeaderValue;

        WrappedRequest(HttpServletRequest request, String token) {
            super(request);
            this.bearerHeaderValue = "Bearer " + token;
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? bearerHeaderValue : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.enumeration(Collections.singletonList(bearerHeaderValue))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
            if (names.stream().noneMatch(HttpHeaders.AUTHORIZATION::equalsIgnoreCase)) {
                names.add(HttpHeaders.AUTHORIZATION);
            }
            return Collections.enumeration(names);
        }
    }
}
