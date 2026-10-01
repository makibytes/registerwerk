package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.ImpersonationMode;
import de.makibytes.registerwerk.auth.internal.SessionStateService.ImpersonationState;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ImpersonationGuardFilter")
class ImpersonationGuardFilterTest {

    private final SessionStateService state = mock(SessionStateService.class);
    private final UUID sid = UUID.randomUUID();
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private MockHttpServletResponse run(ImpersonationMode mode, String method, String path) throws Exception {
        when(state.impersonation(sid)).thenReturn(Optional.of(
                new ImpersonationState(UUID.randomUUID(), UUID.randomUUID(), mode, true, false)));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim("imp", true).claim("jti", sid.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new ImpersonationGuardFilter(state, List.of("POST /api/v1/trading/history/*/confirm-payment", "* /api/v1/me/webhooks/**"))
                .doFilter(req, res, chain);
        return res;
    }

    @Test
    @DisplayName("READ_ONLY blocks writes with IMPERSONATION_READ_ONLY but allows reads and exit")
    void readOnly() throws Exception {
        MockHttpServletResponse res = run(ImpersonationMode.READ_ONLY, "POST", "/api/v1/trading/history/x/confirm-payment");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("IMPERSONATION_READ_ONLY");
        assertThat(run(ImpersonationMode.READ_ONLY, "GET", "/api/v1/assets").getStatus()).isEqualTo(200);
        assertThat(run(ImpersonationMode.READ_ONLY, "POST", "/api/v1/auth/exit-impersonation").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ACT_ON_BEHALF still cannot call the attestation deny-list")
    void actOnBehalfDenyList() throws Exception {
        assertThat(run(ImpersonationMode.ACT_ON_BEHALF, "POST", "/api/v1/trading/history/x/confirm-payment").getStatus())
                .isEqualTo(403);
        assertThat(run(ImpersonationMode.ACT_ON_BEHALF, "DELETE", "/api/v1/me/webhooks/1").getStatus()).isEqualTo(403);
        assertThat(run(ImpersonationMode.ACT_ON_BEHALF, "POST", "/api/v1/trading/listings").getStatus()).isEqualTo(200);
    }
}
