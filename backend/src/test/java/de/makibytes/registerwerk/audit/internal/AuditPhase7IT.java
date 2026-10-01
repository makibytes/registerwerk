package de.makibytes.registerwerk.audit.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 7 L1b: idempotent audit appends (7A-05) and persisted chain-verification verdict (7B-04). */
@ApplicationModuleTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Audit Phase 7: idempotent appends and persisted verification verdict")
class AuditPhase7IT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired AuditEventRecorder recorder;
    @Autowired AuditChainVerificationService verifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private AuditRecord record(UUID subject, UUID recordId) {
        return new AuditRecord("PHASE7_TEST", "Phase7", subject, null, "SYSTEM", Map.of("k", "v"),
                Instant.now(), null, null, recordId);
    }

    /** The recorder is an async module listener: wait for the first row, then let a duplicate (if any) land. */
    private void settle(UUID subject) throws InterruptedException {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).until(() -> rows(subject) >= 1);
        Thread.sleep(1500);
    }

    private int rows(UUID subject) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE subject_id = ?", Integer.class, subject);
    }

    @Test @Order(1)
    @DisplayName("health is UNKNOWN (not UP) and the gauge -1 before any verification was recorded")
    void unknownBeforeFirstRun() {
        verifier.invalidate();
        assertThat(verifier.status()).isEqualTo("UNKNOWN");
        assertThat(verifier.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test @Order(2)
    @DisplayName("the same recordId delivered twice appends one row and the chain stays valid")
    void duplicateDeliverySuppressed() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> recorder.on(record(subject, recordId)));
        tx.executeWithoutResult(s -> recorder.on(record(subject, recordId)));
        settle(subject);
        assertThat(rows(subject)).isEqualTo(1);
        assertThat(verifier.verifyNow().valid()).isTrue();
    }

    @Test @Order(3)
    @DisplayName("two threads delivering the same recordId concurrently append exactly one row")
    void concurrentDuplicateDelivery() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> a = pool.submit(() -> { go.await(); tx.executeWithoutResult(s -> recorder.on(record(subject, recordId))); return null; });
        Future<?> b = pool.submit(() -> { go.await(); tx.executeWithoutResult(s -> recorder.on(record(subject, recordId))); return null; });
        go.countDown();
        a.get();
        b.get();
        pool.shutdown();
        settle(subject);
        assertThat(rows(subject)).isEqualTo(1);
    }

    @Test @Order(4)
    @DisplayName("a recorded broken verdict survives a restart (new reader) and clears only after a valid run AND an ack")
    void brokenVerdictIsSticky() {
        UUID brokenId = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_chain_verification (id, valid, rows_checked, first_broken_seq, reason, source) "
                + "VALUES (?, false, 10, 5, 'tampered', 'NIGHTLY')", brokenId);
        verifier.invalidate();
        assertThat(verifier.status()).isEqualTo("BROKEN");
        assertThat(verifier.health().getStatus()).isEqualTo(Status.DOWN);

        assertThat(verifier.verifyNow().valid()).isTrue();      // a later valid run alone is not enough
        assertThat(verifier.status()).isEqualTo("BROKEN");

        verifier.acknowledge(brokenId, UUID.randomUUID(), "REGISTRY_ADMIN", "investigated");
        assertThat(verifier.status()).isEqualTo("VALID");
        assertThat(verifier.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test @Order(5)
    @DisplayName("verification rows are insert-only (WORM trigger)")
    void verificationRowsAreImmutable() {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_chain_verification SET valid = true"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_chain_verification"))
                .hasMessageContaining("immutable");
    }
}
