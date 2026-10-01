package de.makibytes.registerwerk.idempotency.internal;

import de.makibytes.registerwerk.idempotency.api.IdempotencyRecord;
import de.makibytes.registerwerk.idempotency.api.IdempotencyRecordRepository;
import de.makibytes.registerwerk.idempotency.api.IdempotencyStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Idempotency Phase 7 (7A-07): lease reclaim, canonical hash, no replay of secrets")
class IdempotencyPhase7Test {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claim("sub", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_REGISTRY_ADMIN"))));
    }

    private String hashOf(MockHttpServletRequest request) throws Exception {
        IdempotencyService service = mock(IdempotencyService.class);
        when(service.checkOrStart(any(), any(), any(), anyString()))
                .thenReturn(new IdempotencyService.Outcome.Proceed(UUID.randomUUID()));
        new IdempotencyFilter(service).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(service).checkOrStart(any(), any(), eq("key-0000001"), hash.capture());
        return hash.getValue();
    }

    private MockHttpServletRequest post(String uri, String query, String contentType, byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", uri);
        r.setQueryString(query);
        r.addHeader("Idempotency-Key", "key-0000001");
        if (contentType != null) r.setContentType(contentType);
        r.setContent(body);
        return r;
    }

    @Test
    @DisplayName("?dryRun=true and ?dryRun=false do not hash identically; parameter order does not matter")
    void queryStringIsPartOfTheHash() throws Exception {
        authenticate();
        String dry = hashOf(post("/api/v1/x", "dryRun=true", null, "{}".getBytes()));
        authenticate();
        String real = hashOf(post("/api/v1/x", "dryRun=false", null, "{}".getBytes()));
        authenticate();
        String ab = hashOf(post("/api/v1/x", "a=1&b=2", null, "{}".getBytes()));
        authenticate();
        String ba = hashOf(post("/api/v1/x", "b=2&a=1", null, "{}".getBytes()));
        assertThat(dry).isNotEqualTo(real);
        assertThat(ab).isEqualTo(ba);
    }

    @Test
    @DisplayName("multipart retries with a different boundary hash identically")
    void multipartBoundaryIgnored() throws Exception {
        authenticate();
        MockHttpServletRequest first = post("/api/v1/w", null, "multipart/form-data; boundary=----A1", new byte[100]);
        MockHttpServletRequest second = post("/api/v1/w", null, "multipart/form-data; boundary=----B2", new byte[100]);
        String h1 = hashOf(first);
        authenticate();
        String h2 = hashOf(second);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("a @NoIdempotencyReplay handler's record is released, never completed with a stored body")
    void secretResponseIsNotStored() throws Exception {
        authenticate();
        IdempotencyService service = mock(IdempotencyService.class);
        UUID recordId = UUID.randomUUID();
        when(service.checkOrStart(any(), any(), any(), anyString())).thenReturn(new IdempotencyService.Outcome.Proceed(recordId));
        IdempotencyFilter filter = new IdempotencyFilter(service, r -> false, r -> true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(post("/api/v1/tap", null, null, "{}".getBytes()), response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws java.io.IOException {
                res.getWriter().write("{\"temporaryAccessPass\":\"secret\"}");
            }
        });
        verify(service).release(recordId);
        verify(service, never()).complete(any(), org.mockito.ArgumentMatchers.anyInt(), anyString());
        assertThat(response.getContentAsString()).contains("secret");
    }

    private IdempotencyRecord record(IdempotencyStatus status, Instant createdAt) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.setRequestHash("h");
        r.setStatus(status);
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        return r;
    }

    @Test
    @DisplayName("an IN_PROGRESS record older than the lease is taken over; a fresh one still conflicts")
    void staleInProgressIsReclaimed() {
        IdempotencyRecordRepository repo = mock(IdempotencyRecordRepository.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        IdempotencyService service = new IdempotencyService(repo, Duration.ofMinutes(15), meters);
        UUID scope = UUID.randomUUID();

        when(repo.findForUpdate("USER", scope, "k")).thenReturn(Optional.of(record(IdempotencyStatus.IN_PROGRESS, Instant.now().minusSeconds(60))));
        assertThat(service.checkOrStart("USER", scope, "k", "h")).isInstanceOf(IdempotencyService.Outcome.Conflict.class);

        IdempotencyRecord stale = record(IdempotencyStatus.IN_PROGRESS, Instant.now().minus(Duration.ofHours(2)));
        when(repo.findForUpdate("USER", scope, "k")).thenReturn(Optional.of(stale));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.checkOrStart("USER", scope, "k", "h")).isInstanceOf(IdempotencyService.Outcome.Proceed.class);
        verify(repo).delete(stale);
        assertThat(meters.get("registerwerk_idempotency_reclaimed_total").counter().count()).isEqualTo(1.0);
    }
}
