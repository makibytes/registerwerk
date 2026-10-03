package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 0a C1/C2 against the real filter chain and a real PostgreSQL. Approval tokens are minted through
 * {@code POST /api/v1/auth/step-up} (the production path), never hand-built, so these tests exercise
 * exactly what an operator's browser gets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Dual control: approver tokens and request binding (Wave 0a C1/C2)")
class DualControlHardeningIT {

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
    @Autowired TotpSecretStore secretStore;
    @LocalServerPort int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private AppUser newUser(String prefix, AppUserRole role) {
        AppUser u = new AppUser();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash(encoder.encode("Sup3rSecret!pw"));
        u.setRoles(Set.of(role));
        return users.save(u);
    }

    private AppUser admin(String prefix) {
        return newUser(prefix, AppUserRole.REGISTRY_ADMIN);
    }

    /** A user whose TOTP reset visibly changes state (non-admin, so it never counts towards the bootstrap latch). */
    private AppUser enrolledInvestor() {
        AppUser u = newUser("enrolled", AppUserRole.INVESTOR);
        u.setTotpSecret(secretStore.encrypt(u.getId(), StepUpTokenIssuer.generateBase32Secret()));
        u.setTotpEnabled(true);
        return users.save(u);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    private String session(AppUser user) {
        return TestJwt.mint(SECRET, user.getId(), false, null, null, "REGISTRY_ADMIN");
    }

    /** The caller's own (ordinary) step-up token, minted through the real endpoint. */
    private String stepUp(AppUser user) {
        ResponseEntity<StepUpResponse> r = rest.exchange(url("/api/v1/auth/step-up"), HttpMethod.POST,
                new HttpEntity<>(Map.of("code", "123456", "method", "TOTP"), bearer(session(user))), StepUpResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().stepUpToken();
    }

    /** A second approver's token for exactly {@code action} + {@code target} (+ {@code body}), through the real endpoint. */
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
                new HttpEntity<>(req, bearer(session(approver))), StepUpResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().stepUpToken();
    }

    private ResponseEntity<String> post(String bearerToken, String path, Object body, String approverToken) {
        HttpHeaders h = bearer(bearerToken);
        if (approverToken != null) {
            h.set("X-Dual-Control-Token", approverToken);
        }
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    // ── C1 ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1: B's approver token as the Bearer plus A's own approval must not run the action as B - and the target keeps its TOTP")
    void approverTokenAsBearerIsRefused() {
        AppUser a = admin("attacker");
        AppUser b = admin("victim");
        AppUser target = enrolledInvestor();
        String path = "/api/v1/admin/users/" + target.getId() + "/totp-reset";

        // B approved some unrelated request for A; A keeps the token.
        String bsApproval = approval(b, "TOTP_RESET", "POST /api/v1/admin/users/" + UUID.randomUUID() + "/totp-reset", null);
        // A approves "the real request" themselves.
        String asApproval = approval(a, "TOTP_RESET", "POST " + path, null);

        ResponseEntity<String> r = post(bsApproval, path, null, asApproval);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(target.getId()).orElseThrow().isTotpEnabled())
                .as("the guarded action must not have run").isTrue();
    }

    @Test
    @DisplayName("C1: an approver token is not accepted as the Bearer on any endpoint, gated or not")
    void approverTokenIsNeverAnAuthenticationCredential() {
        AppUser b = admin("approver");
        String token = approval(b, "TOTP_RESET", "POST /api/v1/admin/users/" + UUID.randomUUID() + "/totp-reset", null);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        assertThat(rest.exchange(url("/api/v1/auth/session"), HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // A step-up gated endpoint without a second approver: the account lifecycle call.
        assertThat(post(token, "/api/v1/admin/users/" + UUID.randomUUID() + "/disable", Map.of(), null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("C1: the legitimate flow still works - ordinary step-up token as Bearer, approver token in the header, once")
    void legitimateFlowStillWorks() {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        AppUser target = enrolledInvestor();
        String path = "/api/v1/admin/users/" + target.getId() + "/totp-reset";
        String approval = approval(approver, "TOTP_RESET", "POST " + path, null);

        assertThat(post(stepUp(initiator), path, null, approval).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(users.findById(target.getId()).orElseThrow().isTotpEnabled()).isFalse();
        assertThat(post(stepUp(initiator), path, null, approval).getStatusCode())
                .as("single use").isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── C2: previously unbound reasons ───────────────────────────────────────

    /** One request shape per reason that the shipped configuration used to leave body-unbound. */
    private record Shape(String reason, String path, Map<String, Object> approved, Map<String, Object> tampered) {}

    private static java.util.List<Shape> shapes() {
        UUID entity = UUID.randomUUID();
        return java.util.List.of(
                new Shape("KYC_APPROVE", "/api/v1/entities/" + entity + "/kyc/approve",
                        Map.of("overrideNote", "Checklist reviewed, risk accepted"),
                        Map.of("overrideNote", "Something the approver never saw")),
                new Shape("KYC_JURISDICTION_APPROVE", "/api/v1/entities/" + entity + "/kyc/jurisdictions/DE_EWPG/approve",
                        Map.of("expiresAt", "2030-01-01", "overrideNote", "Checklist reviewed, risk accepted"),
                        Map.of("expiresAt", "2099-01-01", "overrideNote", "Checklist reviewed, risk accepted")),
                new Shape("ADMIN_IMPERSONATION_ACT_ON_BEHALF", "/api/v1/impersonation/act-on-behalf",
                        Map.of("entityId", entity.toString(), "reason", "Customer asked us to correct the order"),
                        Map.of("entityId", UUID.randomUUID().toString(), "reason", "Customer asked us to correct the order")));
    }

    @Test
    @DisplayName("C2: changing the request body after approval is refused for reasons that used to be body-unbound; the approved body passes the check")
    void bodyTamperAfterApprovalIsRefused() {
        for (Shape shape : shapes()) {
            AppUser initiator = admin("init-" + shape.reason());
            AppUser approver = admin("appr-" + shape.reason());
            String approval = approval(approver, shape.reason(), "POST " + shape.path(), shape.approved());

            ResponseEntity<String> tampered = post(stepUp(initiator), shape.path(), shape.tampered(), approval);
            assertThat(tampered.getStatusCode()).as(shape.reason() + ": tampered body").isEqualTo(HttpStatus.FORBIDDEN);

            // The refused attempt must not have burnt the approval: the approved body now goes through the gate
            // (whatever the business layer then says about a made-up entity, it is not a dual-control refusal).
            String second = approval(approver, shape.reason(), "POST " + shape.path(), shape.approved());
            ResponseEntity<String> approved = post(stepUp(initiator), shape.path(), shape.approved(), second);
            assertThat(approved.getStatusCode()).as(shape.reason() + ": approved body").isNotEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
