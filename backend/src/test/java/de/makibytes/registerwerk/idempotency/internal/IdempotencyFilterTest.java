package de.makibytes.registerwerk.idempotency.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyFilter unit tests (Track 6-2)")
class IdempotencyFilterTest {

    @Mock private IdempotencyService service;

    private IdempotencyFilter filter;
    private final UUID entityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication jwtAuth(UUID entityId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("entity_id", entityId.toString())
                .claim("sub", UUID.randomUUID().toString())
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_TRADER")));
    }

    @Test
    @DisplayName("Requests without the Idempotency-Key header pass through untouched")
    void noHeader_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trading/listings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("A GET request with the header still passes through — only mutating methods are protected")
    void getMethodWithHeader_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/trading/history");
        request.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("An unauthenticated mutating request with the header passes through (no tenant to scope by)")
    void noAuthentication_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trading/listings");
        request.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("A first-time request proceeds to the downstream chain and completes the record")
    void firstRequest_proceedsAndCompletes() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(jwtAuth(entityId));
        UUID recordId = UUID.randomUUID();
        when(service.checkOrStart(eq(entityId), eq("key-1"), anyString()))
                .thenReturn(new IdempotencyService.Outcome.Proceed(recordId));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trading/listings");
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{\"quantity\":5}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws java.io.IOException {
                res.setContentType("application/json");
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
                res.getWriter().write("{\"id\":\"new-listing\"}");
            }
        };

        filter.doFilter(request, response, chain);

        verify(service).complete(eq(recordId), eq(201), eq("{\"id\":\"new-listing\"}"));
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"new-listing\"}");
    }

    @Test
    @DisplayName("A replayed outcome short-circuits the chain and returns the stored response")
    void replay_shortCircuitsChain() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(jwtAuth(entityId));
        when(service.checkOrStart(eq(entityId), eq("key-1"), anyString()))
                .thenReturn(new IdempotencyService.Outcome.Replay(201, "{\"id\":\"new-listing\"}"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trading/listings");
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{\"quantity\":5}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainInvoked = {false};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                chainInvoked[0] = true;
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"new-listing\"}");
        assertThat(response.getHeader("X-Idempotent-Replay")).isEqualTo("true");
        verify(service, never()).complete(any(), anyInt(), any());
    }

    @Test
    @DisplayName("A Conflict outcome (reused key, different request) short-circuits with the given status")
    void conflict_shortCircuitsChainWithGivenStatus() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(jwtAuth(entityId));
        when(service.checkOrStart(eq(entityId), eq("key-1"), anyString()))
                .thenReturn(new IdempotencyService.Outcome.Conflict(422, "different request"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trading/listings");
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainInvoked = {false};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                chainInvoked[0] = true;
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("different request");
    }

    @Test
    @DisplayName("An unexpected exception in the idempotency service fails open — the request still proceeds")
    void serviceThrows_failsOpen() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(jwtAuth(entityId));
        when(service.checkOrStart(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trading/listings");
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainInvoked = {false};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                chainInvoked[0] = true;
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked[0]).isTrue();
    }
}
