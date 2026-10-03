package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.accessreview.api.AccessReviewDecision;
import de.makibytes.registerwerk.accessreview.api.AccessReviewItem;
import de.makibytes.registerwerk.accessreview.internal.AccessReviewService;
import de.makibytes.registerwerk.admin.events.OperatorUserInvitedEvent;
import de.makibytes.registerwerk.admin.events.OperatorUserInvitedNotificationEvent;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserActionToken;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.events.PrivilegedAccountChangedEvent;
import de.makibytes.registerwerk.auth.internal.DefaultAdminSeeder;
import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 6 K2: user lifecycle, access review, default admin seeding (6-02, 6-03, 6-04, 6-06). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@Import(UserLifecycleIT.Sink.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("User lifecycle, access review and bootstrap admin")
class UserLifecycleIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    private static final String ADMIN_EMAIL = "k2-admin@test.local";
    private static final String PASSWORD = "Sup3rSecret!";
    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";

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
        r.add("registerwerk.auth.dev-secret", () -> SECRET);
    }

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository users;
    @Autowired AppUserActionTokenRepository actionTokens;
    @Autowired AccessReviewService accessReview;
    @Autowired DefaultAdminSeeder seeder;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Sink recorded;
    @Autowired de.makibytes.registerwerk.shared.SecureLinkPort links;

    /** Collects domain events from every thread (HTTP requests run on server threads). */
    @Component
    static class Sink {
        private final List<Object> events = new CopyOnWriteArrayList<>();
        @EventListener void on(Object e) { events.add(e); }
        <T> Stream<T> stream(Class<T> type) { return events.stream().filter(type::isInstance).map(type::cast); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The bootstrap latch is one-way in production (C3); these tests each start from a fresh installation, so
     * they re-open it the only way possible - bypassing the table's own triggers.
     */
    @BeforeEach
    void freshInstallation() {
        jdbc.execute("ALTER TABLE dual_control_bootstrap DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE dual_control_bootstrap SET completed_at = NULL");
        } finally {
            jdbc.execute("ALTER TABLE dual_control_bootstrap ENABLE TRIGGER USER");
        }
    }

    private static String json(Object body) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body);
    }

    private AppUser newUser(AppUserRole... roles) {
        AppUser u = new AppUser();
        u.setEmail("u-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setRoles(Set.of(roles));
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }

    private AppUser seededAdmin() {
        return users.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();
    }

    private String login(String email) {
        ResponseEntity<LoginResponse> r = rest.postForEntity("/api/v1/public/auth/login",
                new LoginRequest(email, PASSWORD), LoginResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return AuthApiIT.extractSessionToken(r);
    }

    private String stepUp(String sessionToken) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(sessionToken);
        return rest.exchange("/api/v1/auth/step-up", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", "123456"), h), StepUpResponse.class).getBody().stepUpToken();
    }

    private HttpStatus get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return HttpStatus.valueOf(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode().value());
    }

    private ResponseEntity<String> post(String bearer, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    // ── 6-04 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the seeder creates the admin once with must_change_password and never touches it again")
    void seederIsCreateIfAbsentOnly() {
        AppUser admin = seededAdmin();
        assertThat(admin.isMustChangePassword()).isTrue();
        String originalHash = admin.getPasswordHash();
        String rotated = encoder.encode("Rotated-by-the-operator-1");
        jdbc.update("UPDATE app_user SET password_hash = ?, enabled = false, must_change_password = false WHERE id = ?",
                rotated, admin.getId());
        try {
            seeder.run(null);

            AppUser after = seededAdmin();
            assertThat(after.isEnabled()).as("a disabled admin must stay disabled after a boot").isFalse();
            assertThat(after.getPasswordHash()).as("the rotated password must survive a boot").isEqualTo(rotated)
                    .isNotEqualTo(originalHash);
            assertThat(after.isMustChangePassword()).isFalse();
        } finally {
            jdbc.update("UPDATE app_user SET password_hash = ?, enabled = true, must_change_password = true WHERE id = ?",
                    originalHash, admin.getId());
        }
    }

    // ── 6-02 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an invited operator who is disabled before registering cannot complete the registration and stays disabled")
    void withdrawnInviteCannotBeCompleted() {
        String adminToken = login(ADMIN_EMAIL);
        String email = "invitee-" + UUID.randomUUID() + "@test.local";
        ResponseEntity<String> invited = post(stepUp(adminToken), "/api/v1/admin/users",
                Map.of("email", email, "name", "Invitee", "roles", List.of("AUDIT")));
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.OK);
        var invitedEvent = recorded.stream(OperatorUserInvitedNotificationEvent.class)
                .filter(e -> e.email().equals(email)).findFirst().orElseThrow();
        assertThat(invitedEvent.inviteLink()).startsWith("enc:v1:");
        String link = links.open(invitedEvent.inviteLink(), invitedEvent.userId());
        String registrationToken = link.substring(link.lastIndexOf('/') + 1);
        AppUser invitee = users.findByEmailIgnoreCase(email).orElseThrow();

        ResponseEntity<String> disabled = post(stepUp(adminToken), "/api/v1/admin/users/" + invitee.getId() + "/disable", Map.of());
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findById(invitee.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(actionTokens.findByAppUserIdAndTokenTypeAndConsumedAtIsNull(invitee.getId(),
                de.makibytes.registerwerk.auth.api.AppUserActionTokenType.REGISTRATION)).isEmpty();

        ResponseEntity<String> completed = rest.postForEntity("/api/v1/public/company-users/registration/complete",
                Map.of("token", registrationToken, "name", "Invitee", "password", "An0ther-Secret!"), String.class);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        AppUser after = users.findById(invitee.getId()).orElseThrow();
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getPasswordHash()).isNull();
    }

    // ── 6-06 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("inviting a REGISTRY_ADMIN: audit payload carries roles/actor role, bootstrap path is flagged, admins are notified")
    void privilegedInviteIsAuditedWithRoles() {
        String adminToken = login(ADMIN_EMAIL);
        String email = "second-admin-" + UUID.randomUUID() + "@test.local";

        ResponseEntity<String> invited = post(stepUp(adminToken), "/api/v1/admin/users",
                Map.of("email", email, "name", "Second Admin", "roles", List.of("REGISTRY_ADMIN")));
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.OK);

        OperatorUserInvitedEvent event = recorded.stream(OperatorUserInvitedEvent.class)
                .filter(e -> email.equals(e.payload().get("email"))).findFirst().orElseThrow();
        assertThat(event.actorRole()).isEqualTo("REGISTRY_ADMIN");
        assertThat(event.payload()).containsEntry("roles", List.of("REGISTRY_ADMIN"));
        // Fewer than two enrolled administrators: the SRE bootstrap exception applies and is recorded.
        assertThat(event.payload()).containsEntry("bootstrap", true);
        PrivilegedAccountChangedEvent alert = recorded.stream(PrivilegedAccountChangedEvent.class)
                .filter(e -> email.equals(e.email())).findFirst().orElseThrow();
        assertThat(alert.change()).isEqualTo("INVITED");
        assertThat(alert.roles()).contains("REGISTRY_ADMIN");
        users.findByEmailIgnoreCase(email).ifPresent(u -> jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", u.getId()));
    }

    @Test
    @DisplayName("operator user administration needs a step-up token")
    void operatorUserAdminNeedsStepUp() {
        String adminToken = login(ADMIN_EMAIL);
        ResponseEntity<String> r = post(adminToken, "/api/v1/admin/users",
                Map.of("email", "nostepup@test.local", "name", "X", "roles", List.of("AUDIT")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 6-03 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("access review: revoking a non-privileged user disables the account and ends their live session")
    void revokeEndsSession() {
        AppUser reviewer = newUser(AppUserRole.COMPLIANCE_OFFICER);
        AppUser auditor = newUser(AppUserRole.AUDIT);
        String auditorToken = login(auditor.getEmail());
        assertThat(get(auditorToken, "/api/v1/auth/session")).isEqualTo(HttpStatus.OK);

        var campaign = accessReview.startCampaign("Q-revoke", null, reviewer.getId(), "COMPLIANCE_OFFICER");
        AccessReviewItem item = itemFor(campaign.getId(), auditor);
        UUID second = seededAdmin().getId();

        accessReview.recordDecision(campaign.getId(), item.getId(), AccessReviewDecision.REVOKED,
                "left", second, "REGISTRY_ADMIN");

        assertThat(users.findById(auditor.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(get(auditorToken, "/api/v1/auth/session")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(actionTokens.findAll().stream().filter(t -> t.getAppUserId().equals(auditor.getId()))
                .allMatch(t -> t.getConsumedAt() != null)).isTrue();
        // write-once: a second decision is refused
        assertThatThrownBy(() -> accessReview.recordDecision(campaign.getId(), item.getId(),
                AccessReviewDecision.CONFIRMED, null, second, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("access review: the last enabled REGISTRY_ADMIN cannot be revoked, even with a second reviewer")
    void lastRegistryAdminCannotBeRevoked() {
        AppUser seeded = seededAdmin();
        // make the seeded admin the only enabled REGISTRY_ADMIN
        List<UUID> others = jdbc.queryForList("SELECT app_user_id FROM app_user_role WHERE role = 'REGISTRY_ADMIN' AND app_user_id <> ?",
                UUID.class, seeded.getId());
        for (UUID id : others) jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", id);
        AppUser reviewerA = newUser(AppUserRole.COMPLIANCE_OFFICER);
        AppUser reviewerB = newUser(AppUserRole.COMPLIANCE_OFFICER);

        var campaign = accessReview.startCampaign("Q-last-admin", null, reviewerA.getId(), "COMPLIANCE_OFFICER");
        AccessReviewItem item = itemFor(campaign.getId(), seeded);

        AccessReviewItem proposed = accessReview.recordDecision(campaign.getId(), item.getId(),
                AccessReviewDecision.REVOKED, "x", reviewerA.getId(), "COMPLIANCE_OFFICER");
        assertThat(proposed.getDecision()).isEqualTo(AccessReviewDecision.REVOKE_PROPOSED);
        assertThat(seededAdmin().isEnabled()).isTrue();

        assertThatThrownBy(() -> accessReview.recordDecision(campaign.getId(), item.getId(),
                AccessReviewDecision.REVOKED, "y", reviewerB.getId(), "COMPLIANCE_OFFICER"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("REGISTRY_ADMIN");
        assertThat(seededAdmin().isEnabled()).as("the last admin must still be enabled").isTrue();
    }

    @Test
    @DisplayName("access review: a role granted after the snapshot makes the decision STALE and blocks closing")
    void staleSnapshotBlocksDecisionAndClose() {
        AppUser reviewer = newUser(AppUserRole.COMPLIANCE_OFFICER);
        AppUser target = newUser(AppUserRole.AUDIT);
        var campaign = accessReview.startCampaign("Q-stale", null, reviewer.getId(), "COMPLIANCE_OFFICER");
        AccessReviewItem item = itemFor(campaign.getId(), target);

        target.setRoles(Set.of(AppUserRole.AUDIT, AppUserRole.COMPLIANCE_OFFICER));
        users.saveAndFlush(target);

        assertThatThrownBy(() -> accessReview.recordDecision(campaign.getId(), item.getId(),
                AccessReviewDecision.CONFIRMED, null, reviewer.getId(), "COMPLIANCE_OFFICER"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("COMPLIANCE_OFFICER");
        assertThat(accessReview.listItems(campaign.getId()).stream().filter(i -> i.getId().equals(item.getId()))
                .findFirst().orElseThrow().getDecision())
                .as("the STALE flag must survive the refused request").isEqualTo(AccessReviewDecision.STALE);

        AccessReviewItem reopened = accessReview.reopen(campaign.getId(), item.getId(), "roles were changed", seededAdmin().getId(), "REGISTRY_ADMIN");
        assertThat(reopened.getDecision()).isEqualTo(AccessReviewDecision.PENDING);
        assertThat(reopened.getRolesSnapshot()).contains("COMPLIANCE_OFFICER");
    }

    @Test
    @DisplayName("access review decisions over HTTP need a step-up token")
    void decisionNeedsStepUp() {
        AppUser reviewer = newUser(AppUserRole.COMPLIANCE_OFFICER);
        AppUser target = newUser(AppUserRole.AUDIT);
        var campaign = accessReview.startCampaign("Q-http", null, reviewer.getId(), "COMPLIANCE_OFFICER");
        AccessReviewItem item = itemFor(campaign.getId(), target);
        String session = login(reviewer.getEmail());
        String path = "/api/v1/access-reviews/" + campaign.getId() + "/items/" + item.getId() + "/decision";

        assertThat(post(session, path, Map.of("decision", "CONFIRMED")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(stepUp(session), path, Map.of("decision", "CONFIRMED")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 6-06 / K2b: second approver via DualControlGate ──────────────────────

    private ResponseEntity<String> postWithApproval(String bearer, String path, Object body, String approverToken) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearer);
        if (approverToken != null) h.set("X-Dual-Control-Token", approverToken);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private void enrolTotp(boolean enrolled, UUID... ids) {
        for (UUID id : ids) jdbc.update("UPDATE app_user SET totp_enabled = ? WHERE id = ?", enrolled, id);
    }

    @Test
    @DisplayName("privileged invite with two enrolled admins needs a second approver bound to this request; bootstrap=false")
    void privilegedInviteNeedsSecondApproverOutsideBootstrap() {
        AppUser actor = seededAdmin();
        AppUser approver = newUser(AppUserRole.REGISTRY_ADMIN);
        enrolTotp(true, actor.getId(), approver.getId());
        try {
            String email = "gated-" + UUID.randomUUID() + "@test.local";
            Map<String, Object> body = Map.of("email", email, "name", "Gated", "roles", List.of("COMPLIANCE_OFFICER"));
            String session = login(ADMIN_EMAIL);

            assertThat(postWithApproval(stepUp(session), "/api/v1/admin/users", body, null).getStatusCode())
                    .as("step-up alone is not enough").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(users.findByEmailIgnoreCase(email)).isEmpty();

            String wrongTarget = TestJwt.dualControlWithBody(SECRET, approver.getId(), "OPERATOR_USER_INVITE", "POST",
                    "/api/v1/admin/users/" + UUID.randomUUID() + "/enable", json(body), "REGISTRY_ADMIN");
            assertThat(postWithApproval(stepUp(session), "/api/v1/admin/users", body, wrongTarget).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);

            String approval = TestJwt.dualControlWithBody(SECRET, approver.getId(), "OPERATOR_USER_INVITE", "POST",
                    "/api/v1/admin/users", json(body), "REGISTRY_ADMIN");
            assertThat(postWithApproval(stepUp(session), "/api/v1/admin/users", body, approval).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(recorded.stream(OperatorUserInvitedEvent.class)
                    .filter(e -> email.equals(e.payload().get("email"))).findFirst().orElseThrow().payload())
                    .containsEntry("bootstrap", false).containsEntry("roles", List.of("COMPLIANCE_OFFICER"));
            // single use: the same approval cannot be replayed for a second invite
            String email2 = "gated2-" + UUID.randomUUID() + "@test.local";
            assertThat(postWithApproval(stepUp(session), "/api/v1/admin/users",
                    Map.of("email", email2, "name", "Gated2", "roles", List.of("COMPLIANCE_OFFICER")), approval)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            users.findByEmailIgnoreCase(email).ifPresent(u -> jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", u.getId()));
        } finally {
            enrolTotp(false, actor.getId(), approver.getId());
            jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", approver.getId());
        }
    }

    @Test
    @DisplayName("reinstating an access-review REVOKED account needs the second approver even in bootstrap mode")
    void reinstatementNeedsApproverEvenInBootstrap() {
        AppUser reviewer = newUser(AppUserRole.COMPLIANCE_OFFICER);
        AppUser auditor = newUser(AppUserRole.AUDIT);
        AppUser approver = newUser(AppUserRole.REGISTRY_ADMIN);
        var campaign = accessReview.startCampaign("Q-reinstate", null, reviewer.getId(), "COMPLIANCE_OFFICER");
        AccessReviewItem item = itemFor(campaign.getId(), auditor);
        accessReview.recordDecision(campaign.getId(), item.getId(), AccessReviewDecision.REVOKED, "left",
                seededAdmin().getId(), "REGISTRY_ADMIN");
        assertThat(users.findById(auditor.getId()).orElseThrow().isEnabled()).isFalse();
        // bootstrap mode: no TOTP-enrolled admins at all
        String path = "/api/v1/admin/users/" + auditor.getId() + "/enable";
        String session = login(ADMIN_EMAIL);
        Map<String, Object> body = Map.of("reinstatementReason", "Revoked in error, confirmed by HR");

        assertThat(postWithApproval(stepUp(session), path, body, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(auditor.getId()).orElseThrow().isEnabled()).isFalse();

        String approval = TestJwt.dualControlWithBody(SECRET, approver.getId(), "OPERATOR_USER_REINSTATE", "POST", path,
                json(body), "REGISTRY_ADMIN");
        assertThat(postWithApproval(stepUp(session), path, body, approval).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findById(auditor.getId()).orElseThrow().isEnabled()).isTrue();
    }

    private AccessReviewItem itemFor(UUID campaignId, AppUser user) {
        return accessReview.listItems(campaignId).stream()
                .filter(i -> i.getAppUserId().equals(user.getId())).findFirst().orElseThrow();
    }
}
