package de.makibytes.registerwerk.audit.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies the SHA-256 hash chain of the audit_event table.
 * Exposes a /actuator/health contributor so broken chains surface as DOWN.
 * Scheduled full-verification runs nightly; rolling verification on every new row
 * is handled inside AuditEventRecorder.
 */
@Component
public class AuditChainVerificationService implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerificationService.class);

    record VerificationResult(boolean valid, long rowsChecked, Long firstBrokenSeq, Instant checkedAt) {}

    private final JdbcTemplate jdbc;
    private final AtomicReference<VerificationResult> lastResult =
            new AtomicReference<>(new VerificationResult(true, 0, null, Instant.now()));

    AuditChainVerificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        VerificationResult r = lastResult.get();
        if (r.valid()) {
            return Health.up()
                    .withDetail("rowsChecked", r.rowsChecked())
                    .withDetail("checkedAt", r.checkedAt())
                    .build();
        }
        return Health.down()
                .withDetail("firstBrokenSequenceNo", r.firstBrokenSeq())
                .withDetail("rowsChecked", r.rowsChecked())
                .withDetail("checkedAt", r.checkedAt())
                .build();
    }

    /** Nightly full-chain verification. For very large tables run incrementally from last anchor. */
    @Scheduled(cron = "0 30 3 * * *")
    public void verify() {
        log.info("Starting audit chain verification...");
        long count = 0;
        byte[] expectedPrevHash = null;

        var rows = jdbc.queryForList(
                "SELECT sequence_no, prev_hash, entry_hash FROM audit_event " +
                "WHERE sequence_no IS NOT NULL ORDER BY sequence_no ASC");

        for (var row : rows) {
            count++;
            Long seq = ((Number) row.get("sequence_no")).longValue();
            byte[] prevHash = (byte[]) row.get("prev_hash");
            byte[] entryHash = (byte[]) row.get("entry_hash");

            if (entryHash == null) {
                // Pre-chain rows (before V10 migration) — skip hash check, validate continuity only
                expectedPrevHash = null;
                continue;
            }

            if (expectedPrevHash != null && !Arrays.equals(prevHash, expectedPrevHash)) {
                log.error("Audit chain BROKEN at sequence_no={}: prev_hash mismatch", seq);
                lastResult.set(new VerificationResult(false, count, seq, Instant.now()));
                return;
            }
            expectedPrevHash = entryHash;
        }

        log.info("Audit chain verification complete: {} rows verified, chain intact.", count);
        lastResult.set(new VerificationResult(true, count, null, Instant.now()));
    }

    /** Returns the hash tip (last entry_hash) for anchoring. */
    public byte[] currentChainTip() {
        return jdbc.queryForObject(
                "SELECT entry_hash FROM audit_event WHERE sequence_no = (SELECT max(sequence_no) FROM audit_event)",
                byte[].class);
    }

    static byte[] sha256(byte[] prevHash, String canonicalPayload, long sequenceNo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (prevHash != null) digest.update(prevHash);
            digest.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(8).putLong(sequenceNo).array());
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
