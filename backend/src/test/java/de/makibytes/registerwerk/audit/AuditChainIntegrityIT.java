package de.makibytes.registerwerk.audit;

import de.makibytes.registerwerk.audit.api.ChainVerificationView;
import de.makibytes.registerwerk.audit.internal.AuditChainVerificationService;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Audit chain integrity integration test")
class AuditChainIntegrityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired
    private AuditChainVerificationService verificationService;

    @Autowired
    private AuditApi auditApi;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Order(1)
    @DisplayName("Verification passes when audit log is intact")
    void verificationPassesOnCleanLog(Scenario scenario) {
        // Published (not inserted via raw SQL) so every row goes through the real
        // AuditChainAppender write path and gets a real, non-null entry_hash — a raw-SQL
        // insert with the hash columns left NULL would (correctly, since the fix for
        // finding #2) now be reported as a broken chain rather than a tolerated legacy gap.
        UUID subjectId = UUID.randomUUID();
        var event = new KycApprovedEvent(subjectId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of("seq", "0"));

        scenario.publish(event)
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'",
                        Integer.class, subjectId))
                .andVerify(count -> assertThat(count).isGreaterThan(0));

        verificationService.verify();
        Health health = verificationService.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat((Long) health.getDetails().get("rowsChecked")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(2)
    @DisplayName("Audit events inserted directly are persisted and WORM-protected")
    void auditEventsArePersistedAndImmutable() {
        long before = jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class);

        // Insert directly (bypassing async @ApplicationModuleListener) to test the table itself
        UUID subjectId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO audit_event (id, event_type, subject_type, subject_id, actor_role, occurred_at)
            VALUES (gen_random_uuid(), 'CHAIN_INTEGRITY_TEST', 'TEST', ?, 'SYSTEM', now())
            """, subjectId);

        long after = jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class);
        assertThat(after).isGreaterThan(before);

        String idStr = jdbc.queryForObject(
                "SELECT id::text FROM audit_event WHERE subject_id = ? LIMIT 1", String.class, subjectId);
        assertThat(idStr).isNotNull();

        // Verify WORM trigger: UPDATE must throw
        org.assertj.core.api.ThrowableAssert.ThrowingCallable updateAttempt =
            () -> jdbc.update("UPDATE audit_event SET event_type = 'TAMPERED' WHERE subject_id = ?", subjectId);
        org.assertj.core.api.Assertions.assertThatThrownBy(updateAttempt)
            .isInstanceOf(org.springframework.dao.DataAccessException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    @Order(3)
    @DisplayName("on-demand chain verification via AuditApi runs a fresh scan and updates the cached status (finding #6, Phase 11)")
    void verifyChainNowUpdatesStatus() {
        // The prior test intentionally raw-inserts a row with no entry_hash, breaking the chain
        // on purpose (WORM/persistence check, not continuity) — so this asserts verifyChainNow()
        // actually ran a fresh scan and cached it, not that the chain reads as intact.
        ChainVerificationView result = auditApi.verifyChainNow();

        assertThat(result.rowsChecked()).isGreaterThan(0L);
        assertThat(auditApi.chainVerificationStatus()).isEqualTo(result);
    }

}
