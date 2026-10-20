package de.makibytes.registerwerk.auth.internal;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("SpaCsrfConfig.CsrfCookieFilter")
class CsrfCookieFilterTest {

    private final SpaCsrfConfig.CsrfCookieFilter filter = new SpaCsrfConfig.CsrfCookieFilter();

    private CsrfToken run(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        CsrfToken token = mock(CsrfToken.class);
        request.setAttribute(CsrfToken.class.getName(), token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return token;
    }

    @Test
    @DisplayName("a cacheable public GET gets no XSRF-TOKEN cookie: Kong's proxy-cache stores that response and would replay one cookie to every client")
    void publicReadsAreCookieFree() throws Exception {
        verify(run("GET", "/api/v1/public/platform-capabilities"), never()).getToken();
        verify(run("HEAD", "/api/v1/public/platform-capabilities"), never()).getToken();
        verify(run("GET", "/api/v1/public/assets/DE000A1"), never()).getToken();
        verify(run("GET", "/api/v1/public/company-users/registration/abc"), never()).getToken();
    }

    @Test
    @DisplayName("the bootstrap read, public writes, onboarding and every protected request still trigger the cookie")
    void everythingElseStillGetsTheCookie() throws Exception {
        verify(run("GET", "/api/v1/public/auth/config")).getToken();
        verify(run("POST", "/api/v1/public/auth/login")).getToken();
        verify(run("GET", "/api/v1/onboarding/token-info/abc")).getToken();
        verify(run("GET", "/api/v1/entities")).getToken();
        verify(run("POST", "/api/v1/auth/exit-impersonation")).getToken();
    }

    @Test
    @DisplayName("a request without a deferred CSRF token passes through untouched")
    void noTokenAttribute() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/entities");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(eq(request), any());
    }
}
