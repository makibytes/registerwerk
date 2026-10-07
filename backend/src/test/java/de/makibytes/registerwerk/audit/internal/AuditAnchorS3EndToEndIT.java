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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-17 end to end: the daily anchor reaches the Object Lock bucket through the configured sink
 * ({@code registerwerk.audit.anchor-sink=s3}), the verifier reads the newest external anchor back and
 * accepts it, and a forged newer external anchor is reported as a break.
 */
@ApplicationModuleTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Audit anchor -> S3 Object Lock -> verifier (T6-17)")
class AuditAnchorS3EndToEndIT {

    static final String BUCKET = "e2e-audit-anchors";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    // Started eagerly (not @Container): its mapped port is read while the Spring context is being built.
    static final GenericContainer<?> minio = TestMinio.container();

    static {
        minio.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
        registry.add("registerwerk.audit.anchor-sink", () -> "s3");
        registry.add("registerwerk.audit.anchor.s3.bucket", () -> BUCKET);
        registry.add("registerwerk.audit.anchor.s3.prefix", () -> "e2e/");
        registry.add("registerwerk.audit.anchor.s3.endpoint", () -> TestMinio.endpoint(minio));
        registry.add("registerwerk.audit.anchor.s3.region", () -> "us-east-1");
        registry.add("registerwerk.audit.anchor.s3.access-key", () -> TestMinio.ACCESS_KEY);
        registry.add("registerwerk.audit.anchor.s3.secret-key", () -> TestMinio.SECRET_KEY);
        registry.add("registerwerk.audit.anchor.s3.path-style", () -> "true");
        registry.add("registerwerk.audit.anchor.s3.retention-mode", () -> "GOVERNANCE");
        registry.add("registerwerk.audit.anchor.s3.retention-days", () -> "1");
        registry.add("registerwerk.audit.anchor.s3.create-bucket", () -> "true");
    }

    @Autowired
    private AuditAnchorService anchors;

    @Autowired
    private AuditChainVerificationService verification;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("anchor is written to the bucket, read back and accepted; a forged newer external anchor breaks verification")
    void anchorRoundTripAndTamperDetection(Scenario scenario) {
        UUID subjectId = UUID.randomUUID();
        scenario.publish(new KycApprovedEvent(subjectId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of("seq", "0")))
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'",
                        Integer.class, subjectId))
                .andVerify(count -> assertThat(count).isGreaterThan(0));

        assertThat(anchors.anchorNow()).isTrue();

        try (S3Client s3 = TestMinio.client(minio)) {
            var keys = s3.listObjectsV2(b -> b.bucket(BUCKET).prefix("e2e/")).contents();
            assertThat(keys).hasSize(1);
            assertThat(keys.get(0).key()).isEqualTo("e2e/" + LocalDate.now() + ".json");
            assertThat(s3.headObject(b -> b.bucket(BUCKET).key(keys.get(0).key())).objectLockMode().toString())
                    .isEqualTo("GOVERNANCE");

            // the verifier reads the external anchor and finds it consistent with the chain
            var ok = verification.verifyNow();
            assertThat(ok.valid()).as(ok.reason()).isTrue();

            // forge a NEWER external anchor claiming a different hash for the tip sequence
            var tip = jdbc.queryForMap("SELECT sequence_no FROM audit_chain_tip WHERE id = TRUE");
            long seq = ((Number) tip.get("sequence_no")).longValue();
            String forged = "{\"schema\":1,\"anchorDate\":\"" + LocalDate.now().plusDays(1) + "\",\"sequenceNo\":" + seq
                    + ",\"entryHash\":\"" + HexFormat.of().formatHex(new byte[32]) + "\",\"sig\":null}";
            s3.putObject(b -> b.bucket(BUCKET).key("e2e/" + LocalDate.now().plusDays(1) + ".json"),
                    RequestBody.fromString(forged));
        }
        var broken = verification.verifyNow();
        assertThat(broken.valid()).isFalse();
        assertThat(broken.reason()).contains("external anchor hash differs");
    }
}
