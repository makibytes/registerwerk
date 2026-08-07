package de.makibytes.registerwerk.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Resolves the bearer token from the {@code Authorization} header first — step-up/dual-control
 * tokens, service-to-service calls, and the impersonation-exchange step all rely on an explicit
 * header taking priority over the ambient session — and falls back to the {@code rw_session}
 * cookie ({@link SessionCookieService}) when no header is present. This is what makes the
 * cookie transparently authenticate ordinary requests without every call site needing to change.
 */
@Component
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final SessionCookieService cookies;

    public CookieBearerTokenResolver(SessionCookieService cookies) {
        this.cookies = cookies;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String headerToken = headerResolver.resolve(request);
        if (headerToken != null) {
            return headerToken;
        }
        return cookies.readSessionCookie(request).orElse(null);
    }
}
