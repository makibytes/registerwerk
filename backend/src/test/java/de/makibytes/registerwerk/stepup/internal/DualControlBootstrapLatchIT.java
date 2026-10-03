package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 0a C3: the "fewer than two enrolled administrators" bootstrap exception is a one-way door, and
 * switching off a privileged colleague is itself a four-eyes action. Initiators use step-up tokens built
 * with {@link TestJwt} (their TOTP is not under test); approvals are minted through the real endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Dual control: bootstrap latch and privileged account lifecycle (Wave 0a C3)")
class DualControlBootstrapLatchIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.entra-enabled", () -> "false");
        registry.add("registerwerk.auth.dev-secret", () -> SECRET);
    }

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @LocalServerPort int port;

    @BeforeEach
    void freshInstall() {
        // Every test starts as a fresh installation: no enabled or enrolled administrator, latch open.
        jdbc.update("UPDATE app_user SET enabled = false, totp_enabled = false");
        resetLatch();
    }

    /** Test-only: production code has no way back, the table forbids it. */
    private void resetLatch() {
        jdbc.execute("ALTER TABLE dual_control_bootstrap DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE dual_control_bootstrap SET completed_at = NULL");
        } finally {
            jdbc.execute("ALTER TABLE dual_control_bootstrap ENABLE TRIGGER USER");
        }
    }

    private boolean latched() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM dual_control_bootstrap", Boolean.class));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private AppUser account(String prefix, boolean enrolled, AppUserRole... roles) {
        AppUser u = new AppUser();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash(encoder.encode("Sup3rSecret!pw"));
        u.setRoles(Set.of(roles));
        u.setEnabled(true);
        u = users.saveAndFlush(u);
        if (enrolled) {
            jdbc.update("UPDATE app_user SET totp_enabled = true WHERE id = ?", u.getId());
        }
        return u;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    private String stepUp(AppUser u) {
        return TestJwt.mint(SECRET, u.getId(), true, null, null, "REGISTRY_ADMIN");
    }

    private String approval(AppUser approver, String action, String target, Object body) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("code", "123456");
        req.put("method", "TOTP");
        req.put("action", action);
        req.put("target", target);
        if (body != null) {
            req.put("targetBody", body);
        }
        ResponseEntity<StepUpResponse> r = rest.exchange(url("/api/v1/auth/step-up"), HttpMethod.POST,
                new HttpEntity<>(req, bearer(TestJwt.mint(SECRET, approver.getId(), false, null, null, "REGISTRY_ADMIN"))),
                StepUpResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().stepUpToken();
    }

    private ResponseEntity<String> call(HttpMethod method, AppUser initiator, String path, Object body, String approverToken) {
        HttpHeaders h = bearer(stepUp(initiator));
        if (approverToken != null) {
            h.set("X-Dual-Control-Token", approverToken);
        }
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), String.class);
    }

    private Map<String, Object> invite(String prefix) {
        return Map.of("email", prefix + "-" + UUID.randomUUID() + "@test.local", "name", "Invitee",
                "roles", List.of("COMPLIANCE_OFFICER"));
    }

    // ── the latch ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bootstrap: with one enrolled administrator a single step-up still suffices, and the latch stays open")
    void bootstrapStillWorksOnAFreshInstall() {
        AppUser first = account("first", true, AppUserRole.REGISTRY_ADMIN);

        assertThat(call(HttpMethod.POST, first, "/api/v1/admin/users", invite("second"), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(latched()).isFalse();
    }

    @Test
    @DisplayName("the moment two enabled, enrolled administrators exist the latch closes - nothing but that is needed to do so")
    void secondEnrolledAdministratorClosesTheLatch() {
        account("first", true, AppUserRole.REGISTRY_ADMIN);
        assertThat(latched()).isFalse();
        account("second", true, AppUserRole.REGISTRY_ADMIN);
        assertThat(latched()).isTrue();
    }

    @Test
    @DisplayName("C3: once two enrolled administrators have existed, losing one never re-opens the single-step-up path")
    void latchSurvivesLossOfTheSecondAdministrator() {
        AppUser a = account("a", true, AppUserRole.REGISTRY_ADMIN);
        AppUser b = account("b", true, AppUserRole.REGISTRY_ADMIN);
        AppUser c = account("c", false, AppUserRole.REGISTRY_ADMIN);
        // However the colleague went away (access review, SQL, a bug): only one enrolled administrator is left.
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", b.getId());
        Map<String, Object> body = invite("sockpuppet");

        assertThat(call(HttpMethod.POST, a, "/api/v1/admin/users", body, null).getStatusCode())
                .as("single step-up on a privileged invite after the latch").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findByEmailIgnoreCase((String) body.get("email"))).isEmpty();

        String approval = approval(c, "OPERATOR_USER_INVITE", "POST /api/v1/admin/users", body);
        assertThat(call(HttpMethod.POST, a, "/api/v1/admin/users", body, approval).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the latch row cannot be cleared or removed by anything but a superuser bypass")
    void latchIsOneWay() {
        account("a", true, AppUserRole.REGISTRY_ADMIN);
        account("b", true, AppUserRole.REGISTRY_ADMIN);
        assertThat(latched()).isTrue();

        assertThatThrownBy(() -> jdbc.update("UPDATE dual_control_bootstrap SET completed_at = NULL"))
                .hasMessageContaining("one-way");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dual_control_bootstrap")).hasMessageContaining("one-way");
        assertThat(latched()).isTrue();
    }

    // ── privileged lifecycle ─────────────────────────────────────────────────

    @Test
    @DisplayName("C3: disabling a privileged colleague needs a second approver bound to that request")
    void disablingAPrivilegedAccountNeedsASecondApprover() {
        AppUser a = account("a", true, AppUserRole.REGISTRY_ADMIN);
        AppUser b = account("b", true, AppUserRole.REGISTRY_ADMIN);
        AppUser c = account("c", false, AppUserRole.REGISTRY_ADMIN);
        String path = "/api/v1/admin/users/" + b.getId() + "/disable";

        assertThat(call(HttpMethod.POST, a, path, Map.of(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(b.getId()).orElseThrow().isEnabled()).as("B must still be enabled").isTrue();

        String approval = approval(c, "OPERATOR_USER_DISABLE", "POST " + path, Map.of());
        assertThat(call(HttpMethod.POST, a, path, Map.of(), approval).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findById(b.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("C3: deleting a privileged account after the latch needs a second approver even with one enrolled administrator left")
    void deletingAPrivilegedAccountAfterTheLatchNeedsASecondApprover() {
        AppUser a = account("a", true, AppUserRole.REGISTRY_ADMIN);
        AppUser b = account("b", true, AppUserRole.REGISTRY_ADMIN);
        AppUser c = account("c", false, AppUserRole.REGISTRY_ADMIN);
        AppUser victim = account("victim", false, AppUserRole.AUDIT);
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", b.getId());
        String path = "/api/v1/admin/users/" + victim.getId();

        assertThat(call(HttpMethod.DELETE, a, path, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(victim.getId())).isPresent();

        String approval = approval(c, "OPERATOR_USER_DELETE", "DELETE " + path, null);
        assertThat(call(HttpMethod.DELETE, a, path, null, approval).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(users.findById(victim.getId())).isEmpty();
    }

    @Test
    @DisplayName("disabling an ordinary company user still takes only the caller's own step-up")
    void disablingACompanyUserNeedsNoApprover() {
        AppUser a = account("a", true, AppUserRole.REGISTRY_ADMIN);
        account("b", true, AppUserRole.REGISTRY_ADMIN);
        HttpHeaders admin = bearer(TestJwt.mint(SECRET, a.getId(), false, null, null, "REGISTRY_ADMIN"));
        ResponseEntity<EntityResponse> entity = rest.exchange(url("/api/v1/entities"), HttpMethod.POST,
                new HttpEntity<>(new EntityCreateRequest(EntityType.ISSUER, "Lifecycle GmbH " + UUID.randomUUID(),
                        null, "DE", null, null), admin), EntityResponse.class);
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AppUser trader = account("trader", false, AppUserRole.TRADER);
        jdbc.update("UPDATE app_user SET legal_entity_id = ? WHERE id = ?", entity.getBody().id(), trader.getId());

        assertThat(call(HttpMethod.POST, a, "/api/v1/admin/users/" + trader.getId() + "/disable", Map.of(), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findById(trader.getId()).orElseThrow().isEnabled()).isFalse();
    }
}
