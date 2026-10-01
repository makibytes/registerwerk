package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.admin.web.dto.ImpersonateResponse;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.ImpersonationSession;
import de.makibytes.registerwerk.auth.api.ImpersonationSessionRepository;
import de.makibytes.registerwerk.auth.api.SessionRevocationPort;
import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 6 K1: per-request session guard, revocation, step-up-token-as-session, impersonation sessions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Session guard, revocation and impersonation sessions")
class SessionGuardIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    private static final String ADMIN_EMAIL = "guard-admin@test.local";
    private static final String PASSWORD = "Sup3rSecret!";
    private static final String REASON = "support case 4711: customer reported a booking issue";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        r.add("registerwerk.auth.entra-enabled", () -> "false");
        r.add("registerwerk.auth.default-admin.email", () -> ADMIN_EMAIL);
        r.add("registerwerk.auth.default-admin.password", () -> PASSWORD);
        r.add("registerwerk.auth.reject-unknown-users", () -> "true");
    }

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository users;
    @Autowired LegalEntityRepository entities;
    @Autowired ImpersonationSessionRepository sessions;
    @Autowired SessionRevocationPort revocation;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    // ── helpers ──────────────────────────────────────────────────────────────

    private AppUser newUser(AppUserRole role, UUID entityId) {
        AppUser u = new AppUser();
        u.setEmail("u-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setRoles(Set.of(role));
        u.setLegalEntityId(entityId);
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }

    private LegalEntity newEntity(EntityStatus status) {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber("G-" + UUID.randomUUID().toString().substring(0, 8));
        e.setType(EntityType.ISSUER);
        e.setStatus(status);
        e.setCurrentName("Guard Test Entity");
        return entities.saveAndFlush(e);
    }

    private String login(String email) {
        ResponseEntity<LoginResponse> r = rest.postForEntity("/api/v1/public/auth/login",
                new LoginRequest(email, PASSWORD), LoginResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return AuthApiIT.extractSessionToken(r);
    }

    private HttpStatus get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return HttpStatus.valueOf(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode().value());
    }

    private static void passRevocationBoundary() {
        AuthApiIT.sleepPastRevocationBoundary();
    }

    private String stepUp(String sessionToken) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(sessionToken);
        return rest.exchange("/api/v1/auth/step-up", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", "123456"), h), StepUpResponse.class).getBody().stepUpToken();
    }

    private ImpersonateResponse start(String adminToken, UUID entityId, String reason) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(stepUp(adminToken));
        return rest.exchange("/api/v1/impersonation", HttpMethod.POST,
                new HttpEntity<>(reason == null ? Map.of("entityId", entityId)
                        : Map.of("entityId", entityId, "reason", reason), h),
                ImpersonateResponse.class).getBody();
    }

    private static String code(ImpersonateResponse r) {
        return r.handoffUrl().substring(r.handoffUrl().indexOf("#code=") + 6).split("&", 2)[0];
    }

    private ResponseEntity<LoginResponse> exchange(String code) {
        return rest.postForEntity("/api/v1/public/auth/impersonate", Map.of("code", code), LoginResponse.class);
    }

    // ── 6-01 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a disabled user's existing token is rejected")
    void disabledUserTokenRejected() {
        AppUser u = newUser(AppUserRole.TRADER, null);
        String token = login(u.getEmail());
        assertThat(get(token, "/api/v1/auth/session")).isEqualTo(HttpStatus.OK);

        u.setEnabled(false);
        users.saveAndFlush(u);

        assertThat(get(token, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a role downgrade revokes the old token")
    void roleDowngradeRevokes() {
        AppUser u = newUser(AppUserRole.REGISTRY_ADMIN, null);
        String token = login(u.getEmail());
        assertThat(get(token, "/api/v1/entities")).isEqualTo(HttpStatus.OK);

        u.setRoles(Set.of(AppUserRole.AUDIT));
        users.saveAndFlush(u);

        assertThat(get(token, "/api/v1/entities")).isEqualTo(HttpStatus.UNAUTHORIZED);
        passRevocationBoundary();
        assertThat(get(login(u.getEmail()), "/api/v1/auth/session")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("termination of the user's entity ends the session even without a user change")
    void closedEntityEndsSession() {
        LegalEntity e = newEntity(EntityStatus.ACTIVE);
        AppUser u = newUser(AppUserRole.TRADER, e.getId());
        String token = login(u.getEmail());
        assertThat(get(token, "/api/v1/auth/session")).isEqualTo(HttpStatus.OK);

        e.setStatus(EntityStatus.CLOSED);
        entities.saveAndFlush(e);
        // A non-access change (no tokens_valid_after bump) only evicts the guard cache; the closed
        // entity alone must now end the session.
        u.setFullName("Renamed");
        users.saveAndFlush(u);

        assertThat(get(token, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("explicit revokeAll rejects previously issued tokens")
    void revokeAll() {
        AppUser u = newUser(AppUserRole.TRADER, null);
        String token = login(u.getEmail());
        revocation.revokeAll(u.getId());
        assertThat(get(token, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("logout revokes the token server-side: replaying the same JWT fails")
    void logoutRevokes() {
        AppUser u = newUser(AppUserRole.TRADER, null);
        String token = login(u.getEmail());
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Void> out = rest.exchange("/api/v1/public/auth/logout", HttpMethod.POST,
                new HttpEntity<>(h), Void.class);
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get(token, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("select count(*) from session_revocation", Integer.class)).isPositive();
    }

    @Test
    @DisplayName("a step-up token is not accepted as a session bearer on ungated endpoints")
    void stepUpTokenIsNotASession() {
        AppUser admin = newUser(AppUserRole.REGISTRY_ADMIN, null);
        String stepUp = stepUp(login(admin.getEmail()));
        assertThat(get(stepUp, "/api/v1/auth/session")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(stepUp, "/api/v1/entities")).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a token for a user with no account row is rejected")
    void unknownUserRejected() {
        String token = de.makibytes.registerwerk.TestJwt.mint(
                "registerwerk-test-jwt-secret-not-for-production!!", UUID.randomUUID(), false, null, null, "REGISTRY_ADMIN");
        assertThat(get(token, "/api/v1/entities")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 6-31 / 6-32 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("impersonation needs step-up and a reason of at least 15 characters")
    void impersonationRequiresReasonAndStepUp() {
        AppUser admin = newUser(AppUserRole.REGISTRY_ADMIN, null);
        String session = login(admin.getEmail());
        LegalEntity e = newEntity(EntityStatus.ACTIVE);

        HttpHeaders plain = new HttpHeaders();
        plain.setBearerAuth(session);
        ResponseEntity<String> noStepUp = rest.exchange("/api/v1/impersonation", HttpMethod.POST,
                new HttpEntity<>(Map.of("entityId", e.getId(), "reason", REASON), plain), String.class);
        assertThat(noStepUp.getStatusCode().value()).isIn(401, 403);

        HttpHeaders su = new HttpHeaders();
        su.setBearerAuth(stepUp(session));
        ResponseEntity<String> noReason = rest.exchange("/api/v1/impersonation", HttpMethod.POST,
                new HttpEntity<>(Map.of("entityId", e.getId(), "reason", "short"), su), String.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("handoff: no token in the response, code is single use, replay ends the session")
    void handoffSingleUseAndReplayRevokes() {
        AppUser admin = newUser(AppUserRole.REGISTRY_ADMIN, null);
        LegalEntity e = newEntity(EntityStatus.ACTIVE);
        ImpersonateResponse started = start(login(admin.getEmail()), e.getId(), REASON);

        assertThat(started.handoffUrl()).contains("#code=").doesNotContain("token=").doesNotContain("eyJ");
        ImpersonationSession row = sessions.findById(started.sessionId()).orElseThrow();
        assertThat(row.getReason()).isEqualTo(REASON);
        assertThat(row.getExpiresAt()).isBefore(java.time.Instant.now().plusSeconds(1801));

        ResponseEntity<LoginResponse> first = exchange(code(started));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().impersonationMode()).isEqualTo("READ_ONLY");
        String impToken = AuthApiIT.extractSessionToken(first);
        assertThat(get(impToken, "/api/v1/auth/session")).isEqualTo(HttpStatus.OK);

        ResponseEntity<LoginResponse> replay = exchange(code(started));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessions.findById(started.sessionId()).orElseThrow().getEndReason()).isEqualTo("HANDOFF_REPLAY");
        assertThat(get(impToken, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired handoff code is refused")
    void expiredHandoffRefused() {
        AppUser admin = newUser(AppUserRole.REGISTRY_ADMIN, null);
        LegalEntity e = newEntity(EntityStatus.ACTIVE);
        ImpersonateResponse started = start(login(admin.getEmail()), e.getId(), REASON);
        jdbc.update("update impersonation_session set handoff_expires_at = now() - interval '1 second' where id = ?",
                started.sessionId());
        assertThat(exchange(code(started)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("READ_ONLY impersonation cannot write; exit revokes the token")
    void readOnlyBlocksWritesAndExitRevokes() {
        AppUser admin = newUser(AppUserRole.REGISTRY_ADMIN, null);
        LegalEntity e = newEntity(EntityStatus.ACTIVE);
        ImpersonateResponse started = start(login(admin.getEmail()), e.getId(), REASON);
        String impToken = AuthApiIT.extractSessionToken(exchange(code(started)));

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(impToken);
        ResponseEntity<String> write = rest.exchange(
                "/api/v1/trading/history/" + UUID.randomUUID() + "/confirm-payment", HttpMethod.POST,
                new HttpEntity<>(h), String.class);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(write.getBody()).contains("IMPERSONATION_READ_ONLY");

        ResponseEntity<Void> exit = rest.exchange("/api/v1/auth/exit-impersonation", HttpMethod.POST,
                new HttpEntity<>(h), Void.class);
        assertThat(exit.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(sessions.findById(started.sessionId()).orElseThrow().getEndedAt()).isNotNull();
        assertThat(get(impToken, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("act-on-behalf needs a second approver")
    void actOnBehalfNeedsSecondApprover() {
        AppUser admin = newUser(AppUserRole.REGISTRY_ADMIN, null);
        LegalEntity e = newEntity(EntityStatus.ACTIVE);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(stepUp(login(admin.getEmail())));
        ResponseEntity<String> r = rest.exchange("/api/v1/impersonation/act-on-behalf", HttpMethod.POST,
                new HttpEntity<>(Map.of("entityId", e.getId(), "reason", REASON), h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an impersonation session ends when the operator is disabled")
    void actorDisabledEndsImpersonation() {
        AppUser admin = newUser(AppUserRole.REGISTRY_ADMIN, null);
        LegalEntity e = newEntity(EntityStatus.ACTIVE);
        ImpersonateResponse started = start(login(admin.getEmail()), e.getId(), REASON);
        String impToken = AuthApiIT.extractSessionToken(exchange(code(started)));
        assertThat(get(impToken, "/api/v1/auth/session")).isEqualTo(HttpStatus.OK);

        admin.setEnabled(false);
        users.saveAndFlush(admin);
        assertThat(get(impToken, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
