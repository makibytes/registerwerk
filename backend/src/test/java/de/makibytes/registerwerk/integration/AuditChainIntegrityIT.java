package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.audit.internal.AuditChainVerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Audit chain integrity integration test")
class AuditChainIntegrityIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired
    private AuditChainVerificationService verificationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Verification passes when audit log is intact")
    void verificationPassesOnCleanLog() {
        // Insert synchronously — @ApplicationModuleListener fires after tx commit (async),
        // so publishEvent() would leave the chain empty by the time verify() runs.
        for (int i = 0; i < 5; i++) {
            jdbc.update("""
                INSERT INTO audit_event (id, event_type, subject_type, subject_id, actor_role, occurred_at)
                VALUES (gen_random_uuid(), ?, 'TEST', gen_random_uuid(), 'SYSTEM', now())
                """, "VERIFY_TEST_" + i);
        }

        verificationService.verify();
        Health health = verificationService.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat((Long) health.getDetails().get("rowsChecked")).isGreaterThanOrEqualTo(5L);
    }

    @Test
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
}
