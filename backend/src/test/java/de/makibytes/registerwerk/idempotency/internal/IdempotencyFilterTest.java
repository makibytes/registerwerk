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

    private static Authentication operatorAuth(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("sub", userId.toString())
                .claim("roles", List.of("REGISTRY_ADMIN"))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_REGISTRY_ADMIN")));
    }

    private IdempotencyFilter requiredFilter() {
        return new IdempotencyFilter(service, request -> true);
    }

    @Test
    @DisplayName("P4B-7: an operator token WITHOUT entity_id is protected, scoped by its user (sub)")
    void operatorToken_isScopedByUser() throws Exception {
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(operatorAuth(userId));
        when(service.checkOrStart(eq("USER"), eq(userId), eq("mint-key-0001"), anyString()))
                .thenReturn(new IdempotencyService.Outcome.Replay(202, "{\"transactionId\":\"x\"}"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assets/a/deployments/d/issuer/mint");
        request.addHeader("Idempotency-Key", "mint-key-0001");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeader("X-Idempotent-Replay")).isEqualTo("true");
    }

    @Test
    @DisplayName("P4B-7: a mandatory endpoint without the header is rejected 400 IDEMPOTENCY_KEY_REQUIRED, chain untouched")
    void required_missingKey_is400() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(operatorAuth(UUID.randomUUID()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assets/a/deployments/d/issuer/mint");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainInvoked = {false};

        requiredFilter().doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                chainInvoked[0] = true;
            }
        });

        assertThat(chainInvoked[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("P4B-7: a mandatory endpoint rejects a malformed key; a GET on it is not affected")
    void required_invalidKey_is400_andGetIsExempt() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(operatorAuth(UUID.randomUUID()));
        MockHttpServletRequest bad = new MockHttpServletRequest("POST", "/x");
        bad.addHeader("Idempotency-Key", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        requiredFilter().doFilter(bad, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_KEY_INVALID");

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/x");
        MockFilterChain chain = new MockFilterChain();
        requiredFilter().doFilter(get, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("P4B-7: on a mandatory endpoint a bookkeeping failure answers 503 instead of failing open")
    void required_serviceThrows_is503() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(operatorAuth(UUID.randomUUID()));
        when(service.checkOrStart(any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        request.addHeader("Idempotency-Key", "mint-key-0001");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainInvoked = {false};

        requiredFilter().doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                chainInvoked[0] = true;
            }
        });

        assertThat(chainInvoked[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("An exception escaping the downstream chain releases the record and is never retried by the filter")
    void chainThrows_releasesRecord_noSecondInvocation() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(operatorAuth(UUID.randomUUID()));
        UUID recordId = UUID.randomUUID();
        when(service.checkOrStart(any(), any(), any(), any())).thenReturn(new IdempotencyService.Outcome.Proceed(recordId));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        request.addHeader("Idempotency-Key", "mint-key-0001");
        request.setContent("{}".getBytes());
        int[] calls = {0};

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> requiredFilter().doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                            throws jakarta.servlet.ServletException {
                        calls[0]++;
                        throw new jakarta.servlet.ServletException("boom");
                    }
                })).isInstanceOf(jakarta.servlet.ServletException.class);

        assertThat(calls[0]).isEqualTo(1);
        verify(service).complete(eq(recordId), eq(500), anyString());
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
        when(service.checkOrStart(eq("ENTITY"), eq(entityId), eq("key-1"), anyString()))
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
        when(service.checkOrStart(eq("ENTITY"), eq(entityId), eq("key-1"), anyString()))
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
        when(service.checkOrStart(eq("ENTITY"), eq(entityId), eq("key-1"), anyString()))
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
        when(service.checkOrStart(any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));

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
