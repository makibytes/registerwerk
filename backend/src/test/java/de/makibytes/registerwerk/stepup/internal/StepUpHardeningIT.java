package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.internal.LoginAttemptLimiter;
import de.makibytes.registerwerk.stepup.web.dto.StepUpRequest;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
import de.makibytes.registerwerk.stepup.web.dto.TotpEnrollmentConfirmRequest;
import de.makibytes.registerwerk.stepup.web.dto.TotpEnrollmentRequest;
import de.makibytes.registerwerk.stepup.web.dto.TotpEnrollmentResponse;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 / K3 against a real PostgreSQL: dual-control approvals bound to action + target and single
 * use (6-08), TOTP secrets encrypted at rest with state shared through the database (6-09), and the
 * (account, source) login throttle with bounded storage (6-10).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Step-up and login hardening (Phase 6 K3)")
class StepUpHardeningIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";
    private static final String PASSWORD = "Sup3rSecret!pw";

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
        // Real behaviour: no enrolment => no step-up. (The test profile defaults this to true.)
        registry.add("registerwerk.auth.step-up.allow-unenrolled", () -> "false");
        registry.add("registerwerk.auth.login-max-tracked-keys", () -> "120");
    }

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager txManager;
    @Autowired LoginAttemptLimiter limiter;
    @Autowired TotpSecretStore secretStore;
    @Autowired TotpSecretMigration migration;
    @Autowired StepUpTokenIssuer issuer;
    @LocalServerPort int port;

    @BeforeEach
    void cleanThrottle() {
        jdbc.update("DELETE FROM login_attempt");
        limiter.purgeStale(); // also drops the limiter's row-count cache
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private AppUser newUser(String prefix, boolean admin) {
        AppUser u = new AppUser();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setRoles(admin ? Set.of(de.makibytes.registerwerk.auth.api.AppUserRole.REGISTRY_ADMIN)
                : Set.of(de.makibytes.registerwerk.auth.api.AppUserRole.INVESTOR));
        return users.save(u);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    // ── 6-08 dual control ────────────────────────────────────────────────────

    private ResponseEntity<String> reset(UUID initiator, UUID target, String approverToken) {
        HttpHeaders h = bearer(TestJwt.mint(SECRET, initiator, true, null, null, "REGISTRY_ADMIN"));
        if (approverToken != null) {
            h.set("X-Dual-Control-Token", approverToken);
        }
        return rest.exchange(url("/api/v1/admin/users/" + target + "/totp-reset"), HttpMethod.POST,
                new HttpEntity<>(h), String.class);
    }

    private AppUser enrolled(String prefix) {
        AppUser u = newUser(prefix, false);
        u.setTotpSecret(secretStore.encrypt(u.getId(), StepUpTokenIssuer.generateBase32Secret()));
        u.setTotpEnabled(true);
        return users.save(u);
    }

    @Test
    @DisplayName("6-08: an approval bound to another request, an unbound approval, and a replayed approval are all refused; the bound one works once")
    void dualControlIsBoundAndSingleUse() {
        AppUser initiator = newUser("initiator", true);
        AppUser approver = newUser("approver", true);
        AppUser target = enrolled("target");
        AppUser other = enrolled("other");
        String path = "/api/v1/admin/users/" + target.getId() + "/totp-reset";

        // Unbound (old style: scope only) - accepted before K3, now refused.
        String unbound = TestJwt.mint(SECRET, approver.getId(), true, "TOTP_RESET", null, "REGISTRY_ADMIN");
        assertThat(reset(initiator.getId(), target.getId(), unbound).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Bound to a DIFFERENT user's reset: same action, other target.
        String forOther = TestJwt.dualControl(SECRET, approver.getId(), "TOTP_RESET", "POST",
                "/api/v1/admin/users/" + other.getId() + "/totp-reset", "REGISTRY_ADMIN");
        assertThat(reset(initiator.getId(), target.getId(), forOther).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(target.getId()).orElseThrow().isTotpEnabled()).isTrue();

        // Bound to this request: works once.
        String good = TestJwt.dualControl(SECRET, approver.getId(), "TOTP_RESET", "POST", path, "REGISTRY_ADMIN");
        assertThat(reset(initiator.getId(), target.getId(), good).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        AppUser after = users.findById(target.getId()).orElseThrow();
        assertThat(after.isTotpEnabled()).isFalse();
        assertThat(after.getTotpSecret()).isNull();
        assertThat(after.getTokensValidAfter()).as("live sessions are revoked").isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dual_control_token_use WHERE action = 'TOTP_RESET'",
                Integer.class)).isEqualTo(1);

        // Replay of the very same token against the very same request: the action must not run again.
        target = users.findById(target.getId()).orElseThrow();
        target.setTotpSecret(secretStore.encrypt(target.getId(), StepUpTokenIssuer.generateBase32Secret()));
        target.setTotpEnabled(true);
        users.save(target);
        assertThat(reset(initiator.getId(), target.getId(), good).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(target.getId()).orElseThrow().isTotpEnabled()).isTrue();
    }

    @Test
    @DisplayName("6-08: the initiator cannot approve their own reset, and a reset needs the second approver at all")
    void resetNeedsSecondApprover() {
        AppUser initiator = newUser("initiator2", true);
        AppUser target = enrolled("target2");
        String path = "/api/v1/admin/users/" + target.getId() + "/totp-reset";
        assertThat(reset(initiator.getId(), target.getId(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String self = TestJwt.dualControl(SECRET, initiator.getId(), "TOTP_RESET", "POST", path, "REGISTRY_ADMIN");
        assertThat(reset(initiator.getId(), target.getId(), self).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 6-09 TOTP ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6-09: enrolment needs the password; the stored secret is ciphertext; confirm consumes its step; step-up replay is refused")
    void enrolmentFlow() {
        AppUser user = newUser("totp", true);
        String session = TestJwt.mint(SECRET, user.getId(), false, null, null, "REGISTRY_ADMIN");

        ResponseEntity<String> noPassword = rest.postForEntity(url("/api/v1/auth/step-up/enroll"),
                new HttpEntity<>(new TotpEnrollmentRequest("wrong-password"), bearer(session)), String.class);
        assertThat(noPassword.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(user.getId()).orElseThrow().getTotpSecret()).isNull();

        ResponseEntity<TotpEnrollmentResponse> started = rest.postForEntity(url("/api/v1/auth/step-up/enroll"),
                new HttpEntity<>(new TotpEnrollmentRequest(PASSWORD), bearer(session)), TotpEnrollmentResponse.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        String plain = started.getBody().secret();
        String stored = jdbc.queryForObject("SELECT totp_secret FROM app_user WHERE id = ?", String.class, user.getId());
        assertThat(stored).startsWith("enc:v1:").doesNotContain(plain);
        assertThat(jdbc.queryForObject("SELECT totp_secret_kid FROM app_user WHERE id = ?", String.class, user.getId()))
                .isNotBlank();

        long step = Instant.now().getEpochSecond() / 30;
        assertThat(rest.postForEntity(url("/api/v1/auth/step-up/enroll/confirm"),
                new HttpEntity<>(new TotpEnrollmentConfirmRequest(StepUpTokenIssuer.generateTotp(plain, step)), bearer(session)),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The confirm code's step is spent: the same code is a replay.
        ResponseEntity<String> replay = rest.postForEntity(url("/api/v1/auth/step-up"),
                new HttpEntity<>(new StepUpRequest(StepUpTokenIssuer.generateTotp(plain, step), "TOTP", null), bearer(session)),
                String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The next step is accepted once, then is a replay too.
        String next = StepUpTokenIssuer.generateTotp(plain, step + 1);
        assertThat(rest.postForEntity(url("/api/v1/auth/step-up"),
                new HttpEntity<>(new StepUpRequest(next, "TOTP", null), bearer(session)), StepUpResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity(url("/api/v1/auth/step-up"),
                new HttpEntity<>(new StepUpRequest(next, "TOTP", null), bearer(session)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Self-disenrol needs a valid current code; free the replay guard to get one.
        jdbc.update("UPDATE totp_state SET last_accepted_step = 0 WHERE user_id = ?", user.getId());
        assertThat(rest.postForEntity(url("/api/v1/auth/step-up/disenroll"),
                new HttpEntity<>(new TotpEnrollmentConfirmRequest("000000"), bearer(session)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE totp_state SET last_accepted_step = 0, failed_attempts = 0, locked_until = NULL WHERE user_id = ?",
                user.getId());
        assertThat(rest.postForEntity(url("/api/v1/auth/step-up/disenroll"),
                new HttpEntity<>(new TotpEnrollmentConfirmRequest(StepUpTokenIssuer.generateTotp(plain, step + 1)), bearer(session)),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        AppUser after = users.findById(user.getId()).orElseThrow();
        assertThat(after.isTotpEnabled()).isFalse();
        assertThat(after.getTotpSecret()).isNull();
    }

    @Test
    @DisplayName("6-09: replay and lockout state is in the database - a second 'replica' sees the first one's accepted step and failures")
    void replayStateIsSharedAcrossReplicas() {
        AppUser user = newUser("replica", true);
        TotpStateRepository replicaA = new TotpStateRepository(jdbc, txManager);
        TotpStateRepository replicaB = new TotpStateRepository(jdbc, txManager);

        assertThat(replicaA.acceptStep(user.getId(), 1000)).isTrue();
        assertThat(replicaB.acceptStep(user.getId(), 1000)).as("same step on the other replica = replay").isFalse();
        assertThat(replicaB.acceptStep(user.getId(), 999)).as("an earlier step is a replay too").isFalse();
        assertThat(replicaB.acceptStep(user.getId(), 1001)).isTrue();

        // The attempt budget is shared too: reservations on either replica draw from the same counter.
        for (int i = 0; i < TotpStateRepository.MAX_ATTEMPTS; i++) {
            assertThat((i % 2 == 0 ? replicaA : replicaB).reserveAttempt(user.getId())).isTrue();
        }
        assertThat(replicaA.isLocked(user.getId())).isTrue();
        assertThat(replicaB.isLocked(user.getId())).isTrue();
        assertThat(replicaA.reserveAttempt(user.getId())).as("locked: nothing more is granted").isFalse();
        assertThat(replicaB.reserveAttempt(user.getId())).isFalse();

        // reservations are deterministic under contention: exactly MAX_ATTEMPTS of 30 parallel callers get one
        AppUser contended = newUser("contended", true);
        java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.stream.IntStream.range(0, 30).parallel().forEach(i -> {
            if ((i % 2 == 0 ? replicaA : replicaB).reserveAttempt(contended.getId())) {
                granted.incrementAndGet();
            }
        });
        assertThat(granted.get()).isEqualTo(TotpStateRepository.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("C4/H14: parallel wrong guesses cannot exceed the attempt limit - the attempt is reserved before the code is checked")
    void parallelGuessesAreBoundedByTheLockout() throws Exception {
        AppUser user = newUser("guesser", true);
        String secret = StepUpTokenIssuer.generateBase32Secret();
        jdbc.update("UPDATE app_user SET totp_secret = ?, totp_enabled = true WHERE id = ?",
                secretStore.encrypt(user.getId(), secret), user.getId());
        long step = Instant.now().getEpochSecond() / 30;
        Set<String> valid = Set.of(StepUpTokenIssuer.generateTotp(secret, step - 1),
                StepUpTokenIssuer.generateTotp(secret, step), StepUpTokenIssuer.generateTotp(secret, step + 1));

        int parallel = 40;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(parallel);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < parallel; i++) {
                String guess = String.format("%06d", 100_000 + i * 7);
                if (valid.contains(guess)) {
                    guess = "999999";
                }
                String code = guess;
                results.add(executor.submit(() -> {
                    go.await();
                    try {
                        issuer.issueAfterVerification(user.getId(), code, "TOTP");
                        return "ACCEPTED";
                    } catch (org.springframework.security.access.AccessDeniedException e) {
                        return e.getMessage();
                    }
                }));
            }
            go.countDown();
            int verified = 0;
            for (java.util.concurrent.Future<String> f : results) {
                String outcome = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(outcome).isNotEqualTo("ACCEPTED");
                if (outcome.startsWith("Invalid TOTP code. Check")) {
                    verified++;
                }
            }
            assertThat(verified).as("guesses that were actually compared with the secret")
                    .isLessThanOrEqualTo(TotpStateRepository.MAX_ATTEMPTS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM totp_state WHERE user_id = ?", Integer.class, user.getId()))
                .isLessThanOrEqualTo(TotpStateRepository.MAX_ATTEMPTS);
        assertThat(jdbc.queryForObject("SELECT locked_until > now() FROM totp_state WHERE user_id = ?", Boolean.class,
                user.getId())).isTrue();
    }

    @Test
    @DisplayName("6-09: legacy plaintext secrets are encrypted by the startup migration and keep verifying")
    void plaintextSecretsAreMigrated() {
        AppUser user = newUser("legacy", true);
        String legacy = StepUpTokenIssuer.generateBase32Secret();
        jdbc.update("UPDATE app_user SET totp_secret = ?, totp_enabled = true WHERE id = ?", legacy, user.getId());

        assertThat(migration.migrate()).isGreaterThanOrEqualTo(1);

        String stored = jdbc.queryForObject("SELECT totp_secret FROM app_user WHERE id = ?", String.class, user.getId());
        assertThat(stored).startsWith("enc:v1:").doesNotContain(legacy);
        String session = TestJwt.mint(SECRET, user.getId(), false, null, null, "REGISTRY_ADMIN");
        String code = StepUpTokenIssuer.generateTotp(legacy, Instant.now().getEpochSecond() / 30);
        assertThat(rest.postForEntity(url("/api/v1/auth/step-up"),
                new HttpEntity<>(new StepUpRequest(code, "TOTP", null), bearer(session)), StepUpResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 6-10 login throttling ────────────────────────────────────────────────

    private ResponseEntity<String> login(String email, String password, String clientIp) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Forwarded-For", clientIp); // honoured: the test client is a trusted (loopback) proxy
        return rest.exchange(url("/api/v1/public/auth/login"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password), h), String.class);
    }

    @Test
    @DisplayName("6-10: five failures from IP A lock the pair for A only - the real user still logs in from IP B")
    void lockIsPerAccountAndSource() throws Exception {
        AppUser user = newUser("victim", true);
        for (int i = 0; i < 5; i++) {
            assertThat(login(user.getEmail(), "wrong", "198.51.100.1").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(login(user.getEmail(), PASSWORD, "198.51.100.1").getStatusCode())
                .as("locked pair, even with the right password").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Thread.sleep(1_200); // the account-wide delay after five failures (1 s) is not a lock
        assertThat(login(user.getEmail(), PASSWORD, "198.51.100.2").getStatusCode())
                .as("the legitimate user from another address is not locked out").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("6-10: failures from many addresses only delay the account (a short 'retry after'), they never lock it")
    void distributedFailuresOnlyDelay() {
        AppUser user = newUser("distributed", true);
        for (int i = 0; i < 6; i++) {
            limiter.recordFailure(user.getEmail(), "192.0.2." + (10 + i));
        }
        LoginAttemptLimiter.Decision d = limiter.check(user.getEmail(), "192.0.2.99");
        assertThat(d.blocked()).isTrue();
        assertThat(d.retryAfterSeconds()).isBetween(1L, 5L);
        // The wait is over once the delay since the last failure has passed; the account was never locked.
        jdbc.update("UPDATE login_attempt SET updated_at = now() - interval '10 seconds' WHERE login_key = ?",
                "a|" + user.getEmail().toLowerCase());
        assertThat(limiter.check(user.getEmail(), "192.0.2.99").blocked()).isFalse();
        assertThat(login(user.getEmail(), PASSWORD, "192.0.2.99").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("6-10: a password spray across 30+ e-mails from one address is throttled")
    void sprayFromOneAddressIsThrottled() {
        AppUser real = newUser("spray-real", true);
        for (int i = 0; i < 30; i++) {
            assertThat(login("nobody" + i + "@test.local", "Spring2026!", "203.0.113.77").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(login(real.getEmail(), PASSWORD, "203.0.113.77").getStatusCode())
                .as("the spraying address is refused").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login(real.getEmail(), PASSWORD, "203.0.113.78").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("6-10: lock duration doubles per episode, and each episode is audited once")
    void exponentialBackoffAndAudit() throws Exception {
        String email = "backoff-" + UUID.randomUUID() + "@test.local";
        String ip = "198.51.100.200";
        for (int i = 0; i < 5; i++) limiter.recordFailure(email, ip);
        assertThat(limiter.check(email, ip).blocked()).isTrue();
        double first = lockSeconds(email, ip);
        assertThat(first).isBetween(14 * 60.0, 15 * 60.0 + 5);

        jdbc.update("UPDATE login_attempt SET locked_until = now() - interval '1 second' WHERE login_key = ?",
                "p|" + email + "|" + ip);
        jdbc.update("UPDATE login_attempt SET updated_at = now() - interval '1 minute' WHERE login_key = ?", "a|" + email);
        assertThat(limiter.check(email, ip).blocked()).isFalse();
        for (int i = 0; i < 5; i++) limiter.recordFailure(email, ip);
        double second = lockSeconds(email, ip);
        assertThat(second).isBetween(29 * 60.0, 30 * 60.0 + 5);

        UUID subject = UUID.nameUUIDFromBytes(("login:" + email).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant deadline = Instant.now().plusSeconds(15);
        Integer events = 0;
        while (Instant.now().isBefore(deadline)) {
            events = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type = 'LOGIN_LOCKED' AND subject_id = ?",
                    Integer.class, subject);
            if (events != null && events >= 2) break;
            Thread.sleep(250);
        }
        assertThat(events).as("one LOGIN_LOCKED audit event per episode, not per blocked request").isEqualTo(2);
    }

    private double lockSeconds(String email, String ip) {
        return jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (locked_until - now())) FROM login_attempt WHERE login_key = ?",
                Double.class, "p|" + email + "|" + ip);
    }

    @Test
    @DisplayName("6-10: login_attempt stays bounded under an e-mail flood and is emptied by the purge")
    void tableIsBoundedAndPurged() {
        for (int i = 0; i < 400; i++) {
            limiter.recordFailure("random-" + UUID.randomUUID() + "@test.local", "203.0.113.5");
        }
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM login_attempt", Integer.class);
        assertThat(rows).as("row-count guard (cap 120)").isLessThanOrEqualTo(135);

        jdbc.update("UPDATE login_attempt SET updated_at = now() - interval '3 hours', locked_until = NULL, lock_episodes = 0");
        assertThat(limiter.purgeStale()).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt", Integer.class)).isZero();
    }
}
