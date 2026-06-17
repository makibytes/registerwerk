package de.makibytes.registerwerk.audit;

import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
@DisplayName("Audit module — Scenario-based event flow (Modulith 2.1)")
class AuditModuleScenarioIT {

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
    JdbcTemplate jdbc;

    @Test
    @DisplayName("KycApprovedEvent published from KYC module is persisted by AuditEventRecorder")
    void auditCapturesKycApprovedEvent(Scenario scenario) {
        var entityId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var event = new KycApprovedEvent(entityId, actorId, "REGISTRY_ADMIN",
                Map.of("jurisdiction", "DE"));

        scenario.publish(event)
                .andWaitForStateChange(() ->
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'",
                                Integer.class, entityId))
                .andVerify(count -> assertThat(count).isGreaterThan(0));
    }
}
