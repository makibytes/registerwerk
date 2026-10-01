package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Audit signing watermark: NULL entry_sig after the watermark is a break")
class AuditSigningWatermarkIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
        registry.add("registerwerk.audit.signing.provider", () -> "ENV_VAR");
        registry.add("registerwerk.audit.signing.seed", () -> "integration-test-signing-seed");
    }

    @Autowired AuditChainVerificationService verifier;
    @Autowired JdbcTemplate jdbc;

    @Test
    void nullSignatureAfterWatermarkFailsVerification(Scenario scenario) {
        UUID subject = UUID.randomUUID();
        scenario.publish(new KycApprovedEvent(subject, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of()))
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE subject_id = ?", Integer.class, subject))
                .andVerify(c -> assertThat(c).isGreaterThan(0));
        assertThat(jdbc.queryForObject("SELECT signing_from_seq FROM audit_chain_meta", Long.class)).isNotNull();
        assertThat(verifier.verifyNow().valid()).isTrue();

        byte[] sig = jdbc.queryForObject("SELECT entry_sig FROM audit_event WHERE subject_id = ?", byte[].class, subject);
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_immutable");
        jdbc.update("UPDATE audit_event SET entry_sig = NULL WHERE subject_id = ?", subject);
        var r = verifier.verifyNow();
        assertThat(r.valid()).isFalse();
        assertThat(r.reason()).contains("watermark");
        jdbc.update("UPDATE audit_event SET entry_sig = ? WHERE subject_id = ?", sig, subject);
        jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_immutable");
        assertThat(verifier.verifyNow().valid()).isTrue();
    }
}
