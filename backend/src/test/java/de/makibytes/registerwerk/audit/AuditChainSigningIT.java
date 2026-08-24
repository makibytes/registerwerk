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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of : with audit signing enabled, newly appended
 * rows get a real Ed25519 {@code entry_sig}, and chain verification checks it — proving the
 * wiring works, not just the standalone signer (see EnvVarEd25519SigningKeyProviderTest).
 */
@ApplicationModuleTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Audit chain signing integration test")
class AuditChainSigningIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
        registry.add("registerwerk.audit.signing.provider", () -> "ENV_VAR");
        registry.add("registerwerk.audit.signing.seed", () -> "integration-test-signing-seed");
    }

    @Autowired
    private AuditChainVerificationService verificationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a signed audit event gets a real entry_sig, and verification passes")
    void signedEvent_getsEntrySig_andVerifies(Scenario scenario) {
        UUID subjectId = UUID.randomUUID();
        var event = new KycApprovedEvent(subjectId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of());

        scenario.publish(event)
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT entry_sig IS NOT NULL FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'",
                        Boolean.class, subjectId))
                .andVerify(hasSig -> assertThat(hasSig).isTrue());

        verificationService.verify();
        Health health = verificationService.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    @DisplayName("a tampered entry_sig fails verification")
    void tamperedEntrySig_failsVerification(Scenario scenario) {
        UUID subjectId = UUID.randomUUID();
        var event = new KycApprovedEvent(subjectId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of());

        scenario.publish(event)
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT entry_sig IS NOT NULL FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'",
                        Boolean.class, subjectId))
                .andVerify(hasSig -> assertThat(hasSig).isTrue());

        // Simulate tampering: this bypasses the WORM trigger the same way a privileged
        // connection would — the trigger only blocks the app role, not a superuser session.
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_immutable");
        jdbc.update("UPDATE audit_event SET entry_sig = decode('00112233445566778899aabbccddeeff00112233445566778899aabbccddee', 'hex') "
                + "WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", subjectId);
        jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_immutable");

        verificationService.verify();
        Health health = verificationService.health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }
}
