package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.ImpersonationMode;
import de.makibytes.registerwerk.auth.api.ImpersonationSession;
import de.makibytes.registerwerk.auth.api.ImpersonationSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 5b item 2: in a concurrent replay of the one-time handoff code the loser held a stale row (loaded
 * before the winner committed), so the "a replay ends the session" rule never fired.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Impersonation handoff replay detection is race-free")
class ImpersonationReplayIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> "integration-test-jwt-secret-32-bytes!!");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!Pass");
    }

    @Autowired ImpersonationSessionService service;
    @Autowired ImpersonationSessionRepository sessions;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    private String newSession(String code) {
        AppUser actor = users.findByEmailIgnoreCase("admin@test.local").orElseThrow();
        Instant now = Instant.now();
        sessions.saveAndFlush(new ImpersonationSession(UUID.randomUUID(), actor.getId(), UUID.randomUUID(),
                ImpersonationMode.READ_ONLY, "replay test", null, null, now.plus(10, ChronoUnit.MINUTES),
                ImpersonationSession.hashHandoffCode(code), now.plus(60, ChronoUnit.SECONDS)));
        return ImpersonationSession.hashHandoffCode(code);
    }

    private Map<String, Object> row(String hash) {
        return jdbc.queryForMap("SELECT ended_at, end_reason, handoff_consumed_at FROM impersonation_session "
                + "WHERE handoff_code_hash = ?", hash);
    }

    @Test
    void replayerHoldingAStaleRowStillEndsTheSession() throws Exception {
        String code = "code-" + UUID.randomUUID();
        String hash = newSession(code);
        TransactionTemplate tx = new TransactionTemplate(tm);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                // the replayer has already loaded the row (unconsumed) when the winner exchanges and commits
                assertThat(sessions.findByHandoffCodeHash(hash)).isPresent();
                try {
                    assertThat(pool.submit(() -> service.exchange(code)).get()).isPresent();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertThat(service.exchange(code)).as("replay yields no second token").isEmpty();
            });
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> r = row(hash);
        assertThat(r.get("handoff_consumed_at")).as("consumption stamp is never cleared").isNotNull();
        assertThat(r.get("ended_at")).as("replay must end the session").isNotNull();
        assertThat(r.get("end_reason")).isEqualTo("HANDOFF_REPLAY");
    }

    @Test
    void twoConcurrentExchangesYieldOneTokenAndEndTheSession() throws Exception {
        String code = "code-" + UUID.randomUUID();
        String hash = newSession(code);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Optional<ImpersonationSessionService.Exchange>>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return service.exchange(code);
                }));
            }
            go.countDown();
            long tokens = 0;
            for (var f : results) {
                if (f.get().isPresent()) {
                    tokens++;
                }
            }
            assertThat(tokens).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> r = row(hash);
        assertThat(r.get("end_reason")).isEqualTo("HANDOFF_REPLAY");
        assertThat(r.get("ended_at")).isNotNull();
        assertThat(r.get("handoff_consumed_at")).isNotNull();
    }
}
