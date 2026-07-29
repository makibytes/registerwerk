package de.makibytes.registerwerk.audit;

import de.makibytes.registerwerk.audit.internal.AuditChainVerificationService;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

/**
 * Kept in its own test class (own Testcontainers instance) rather than alongside
 * {@link AuditChainIntegrityIT} because this test deliberately corrupts the chain — sharing
 * a database with tests that expect an intact/UP chain would make their outcome depend on
 * JUnit's (unspecified) method execution order within the class.
 */
@ApplicationModuleTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Audit chain verification — fails closed on an unchained row (finding #2)")
class AuditChainUnchainedRowIT {

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
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("A NULL entry_hash row beyond the configured legacy-gap cutoff fails verification")
    void verificationFailsOnUnchainedRow(Scenario scenario) {
        var event = new KycApprovedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN", Map.of());
        scenario.publish(event)
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE event_type = 'KYC_APPROVED'", Integer.class))
                .andVerify(count -> assertThat(count).isGreaterThan(0));

        // Simulate an out-of-band insert bypassing AuditChainAppender entirely (e.g. a
        // privileged connection, or a bug in some other future writer) — sequence_no is
        // taken from the real sequence so it sorts after every legitimately chained row,
        // and entry_hash is left NULL. With the default legacy-gap cutoff of 0, this must
        // fail verification rather than being silently treated as a tolerated legacy reset.
        jdbc.update("""
            INSERT INTO audit_event (id, event_type, subject_type, subject_id, actor_role, occurred_at, sequence_no)
            VALUES (gen_random_uuid(), 'UNCHAINED_TEST', 'TEST', gen_random_uuid(), 'SYSTEM', now(), nextval('audit_event_seq'))
            """);

        verificationService.verify();
        Health health = verificationService.health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }
}
