package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

/** Phase 6 / K4: v1 rows still verify next to v2 rows; tamper, truncation and anchor detection. */
@ApplicationModuleTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Audit integrity (canon v2, tail/head truncation, anchors, dead letter)")
class AuditIntegrityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired AuditChainVerificationService verifier;
    @Autowired AuditCanonicalJson canonical;
    @Autowired AuditAnchorService anchors;
    @Autowired AuditResubmissionJob resubmission;
    @Autowired AuditApi auditApi;
    @Autowired JdbcTemplate jdbc;

    private void publishAndWait(Scenario scenario, UUID subject) {
        scenario.publish(new KycApprovedEvent(subject, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of()))
                .andWaitForStateChange(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE subject_id = ?", Integer.class, subject))
                .andVerify(c -> assertThat(c).isGreaterThan(0));
    }

    private void noTrigger(Runnable r) {
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_immutable");
        try { r.run(); } finally {
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_immutable");
        }
    }

    @Test @Order(1)
    @DisplayName("legacy v1 rows verify together with new v2 rows; new rows are v2 with event time and recorded_at")
    void v1AndV2Coexist(Scenario scenario) {
        // Append a v1 row exactly as the pre-K4 writer did, onto the current tip.
        byte[] prev = jdbc.queryForObject("SELECT entry_hash FROM audit_chain_tip", byte[].class);
        long seq = jdbc.queryForObject("SELECT nextval('audit_event_seq')", Long.class);
        UUID subj = UUID.randomUUID();
        String json = canonical.canonicalize("LEGACY_V1", "TEST", subj, Map.of("a", "b"));
        byte[] hash = AuditChainVerificationService.sha256(prev, json, seq);
        jdbc.update("""
                INSERT INTO audit_event (event_type, subject_type, subject_id, payload, sequence_no, prev_hash, entry_hash)
                VALUES ('LEGACY_V1','TEST',?, '{"a":"b"}'::jsonb, ?, ?, ?)
                """, subj, seq, prev, hash);
        jdbc.update("UPDATE audit_chain_tip SET entry_hash = ?, sequence_no = ?, updated_at = now()", hash, seq);

        UUID subject = UUID.randomUUID();
        publishAndWait(scenario, subject);

        assertThat(jdbc.queryForObject("SELECT canon_version FROM audit_event WHERE event_type='LEGACY_V1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT canon_version FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", Integer.class, subject)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT recorded_at >= occurred_at FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", Boolean.class, subject)).isTrue();
        assertThat(verifier.verifyNow().valid()).isTrue();
    }

    @Test @Order(2)
    @DisplayName("tampering actor_id of a v2 row is detected (today's v1 hash did not cover it)")
    void tamperedActorDetected(Scenario scenario) {
        UUID subject = UUID.randomUUID();
        publishAndWait(scenario, subject);
        UUID original = jdbc.queryForObject("SELECT actor_id FROM audit_event WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", UUID.class, subject);
        noTrigger(() -> jdbc.update("UPDATE audit_event SET actor_id = ? WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", UUID.randomUUID(), subject));
        var broken = verifier.verifyNow();
        assertThat(broken.valid()).isFalse();
        noTrigger(() -> jdbc.update("UPDATE audit_event SET actor_id = ? WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", original, subject));
        noTrigger(() -> jdbc.update("UPDATE audit_event SET occurred_at = occurred_at + interval '1 second' WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", subject));
        assertThat(verifier.verifyNow().valid()).isFalse();
        noTrigger(() -> jdbc.update("UPDATE audit_event SET occurred_at = occurred_at - interval '1 second' WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", subject));
        assertThat(verifier.verifyNow().valid()).isTrue();
    }

    @Test @Order(3)
    @DisplayName("deleting the newest rows and resetting the tip is caught by the signed daily anchor")
    void tailTruncationDetected(Scenario scenario) {
        publishAndWait(scenario, UUID.randomUUID());
        anchors.anchorNow();
        long anchorSeq = jdbc.queryForObject("SELECT sequence_no FROM audit_chain_anchor ORDER BY sequence_no DESC LIMIT 1", Long.class);
        jdbc.execute("DROP TABLE IF EXISTS audit_bak");
        jdbc.execute("CREATE TABLE audit_bak AS SELECT * FROM audit_event WHERE sequence_no = " + anchorSeq);
        byte[] tipHash = jdbc.queryForObject("SELECT entry_hash FROM audit_chain_tip", byte[].class);
        byte[] prev = jdbc.queryForObject("SELECT prev_hash FROM audit_event WHERE sequence_no = ?", byte[].class, anchorSeq);
        long prevSeq = jdbc.queryForObject("SELECT max(sequence_no) FROM audit_event WHERE sequence_no < ?", Long.class, anchorSeq);
        noTrigger(() -> jdbc.update("DELETE FROM audit_event WHERE sequence_no = ?", anchorSeq));
        jdbc.update("UPDATE audit_chain_tip SET entry_hash = ?, sequence_no = ?", prev, prevSeq);

        var r = verifier.verifyNow();
        assertThat(r.valid()).isFalse();
        assertThat(r.reason()).contains("anchor");

        noTrigger(() -> jdbc.execute("INSERT INTO audit_event SELECT * FROM audit_bak"));
        jdbc.update("UPDATE audit_chain_tip SET entry_hash = ?, sequence_no = ?", tipHash, anchorSeq);
        assertThat(verifier.verifyNow().valid()).isTrue();
    }

    @Test @Order(4)
    @DisplayName("removing the first row (head truncation) is detected")
    void headTruncationDetected() {
        long first = jdbc.queryForObject("SELECT min(sequence_no) FROM audit_event", Long.class);
        jdbc.execute("DROP TABLE IF EXISTS audit_bak");
        jdbc.execute("CREATE TABLE audit_bak AS SELECT * FROM audit_event WHERE sequence_no = " + first);
        noTrigger(() -> jdbc.update("DELETE FROM audit_event WHERE sequence_no = ?", first));
        var r = verifier.verifyNow();
        assertThat(r.valid()).isFalse();
        noTrigger(() -> jdbc.execute("INSERT INTO audit_event SELECT * FROM audit_bak"));
        assertThat(verifier.verifyNow().valid()).isTrue();
    }

    @Test @Order(5)
    @DisplayName("an unknown canon_version fails verification")
    void unknownVersionFails(Scenario scenario) {
        UUID subject = UUID.randomUUID();
        publishAndWait(scenario, subject);
        noTrigger(() -> jdbc.update("UPDATE audit_event SET canon_version = 9 WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", subject));
        assertThat(verifier.verifyNow().valid()).isFalse();
        noTrigger(() -> jdbc.update("UPDATE audit_event SET canon_version = 2 WHERE subject_id = ? AND event_type = 'KYC_APPROVED'", subject));
        assertThat(verifier.verifyNow().valid()).isTrue();
    }

    @Test @Order(6)
    @DisplayName("TRUNCATE of audit_event is refused and the signing watermark is write-once")
    void truncateAndWatermarkGuards() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_event"))
                .hasMessageContaining("truncated");
        jdbc.update("UPDATE audit_chain_meta SET signing_from_seq = 5 WHERE signing_from_seq IS NULL");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE audit_chain_meta SET signing_from_seq = 99"))
                .hasMessageContaining("write-once");
    }

    @Test @Order(7)
    @DisplayName("a publication that exhausted its attempts is dead-lettered, not retried forever")
    void poisonDeadLettered() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_attempts)
                VALUES (?, 'de.makibytes.registerwerk.audit.internal.AuditEventRecorder.on(de.makibytes.registerwerk.audit.internal.AuditRecord)',
                        'x.Poison', '{}', now() - interval '1 hour', 25)
                """, id);
        resubmission.moveToDeadLetter();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event_dead_letter WHERE publication_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test @Order(8)
    @DisplayName("export API exposes tip and v2 fields")
    void chainTipAndView(Scenario scenario) {
        publishAndWait(scenario, UUID.randomUUID());
        var tip = auditApi.chainTip();
        assertThat(tip.sequenceNo()).isNotNull();
        var rows = auditApi.findForExport(null, null, null, null, null, null, 0L, org.springframework.data.domain.PageRequest.of(0, 1000));
        assertThat(rows).isSortedAccordingTo(java.util.Comparator.comparing(v -> v.sequenceNo()));
        assertThat(rows.get(rows.size() - 1).entryHashHex()).isEqualTo(tip.entryHashHex());
    }
}
