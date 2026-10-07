package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditAnchor;
import de.makibytes.registerwerk.audit.api.AuditAnchorSink;
import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Daily signed anchor of the chain tip (6-11): stored in {@code audit_chain_anchor} (immutable) and
 * handed to the optional {@link AuditAnchorSink}. The verifier re-checks every anchor against the
 * chain, so removing tail rows after an anchor was taken is detected.
 */
@Component
class AuditAnchorService {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorService.class);

    private final JdbcTemplate jdbc;
    private final Optional<SigningKeyProvider> signingKeyProvider;
    private final Optional<AuditAnchorSink> sink;

    AuditAnchorService(JdbcTemplate jdbc, Optional<SigningKeyProvider> signingKeyProvider,
                       Optional<AuditAnchorSink> sink) {
        this.jdbc = jdbc;
        this.signingKeyProvider = signingKeyProvider;
        this.sink = sink;
    }

    /** What is signed for an anchor: SHA-256(sequenceNo as 8 bytes || entryHash). */
    static byte[] anchorDigest(long sequenceNo, byte[] entryHash) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(ByteBuffer.allocate(8).putLong(sequenceNo).array());
            d.update(entryHash);
            return d.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @SchedulerLock(name = "auditAnchorDaily", lockAtMostFor = "PT10M")
    @Scheduled(cron = "0 5 0 * * *")
    void daily() {
        anchorNow();
    }

    /** Anchors the current tip (idempotent per day). Returns false when there is nothing to anchor. */
    boolean anchorNow() {
        var tip = jdbc.queryForMap("SELECT entry_hash, sequence_no FROM audit_chain_tip WHERE id = TRUE");
        byte[] hash = (byte[]) tip.get("entry_hash");
        Number seq = (Number) tip.get("sequence_no");
        if (hash == null || seq == null) {
            return false;
        }
        byte[] sig = signingKeyProvider.map(p -> p.sign(anchorDigest(seq.longValue(), hash))).orElse(null);
        int inserted = jdbc.update("""
                INSERT INTO audit_chain_anchor (kind, anchor_date, sequence_no, entry_hash, sig)
                VALUES ('DAILY', ?, ?, ?, ?) ON CONFLICT (kind, anchor_date) DO NOTHING
                """, LocalDate.now(), seq.longValue(), hash, sig);
        if (inserted == 1) {
            HexFormat h = HexFormat.of();
            try {
                sink.ifPresent(s -> s.publish(new AuditAnchor(LocalDate.now(), seq.longValue(),
                        h.formatHex(hash), sig != null ? h.formatHex(sig) : null)));
            } catch (RuntimeException e) {
                // Never blocks anchoring or audit writes; the sink counts the failure (alert metric) and
                // publishPending() retries the local anchor hourly.
                log.error("Publishing the audit anchor to the external sink failed (kept locally, will retry)", e);
            }
            log.info("Audit chain anchored at sequence_no={}", seq);
        }
        return inserted == 1;
    }

    /** Most recent local daily anchors re-offered to the sink per run (bounds a long outage's backlog). */
    static final int PENDING_LIMIT = 31;

    @SchedulerLock(name = "auditAnchorSinkRetry", lockAtMostFor = "PT10M")
    @Scheduled(cron = "0 35 * * * *")
    void retryPending() {
        sink.ifPresent(this::publishPending);
    }

    /**
     * Publishes the newest {@link #PENDING_LIMIT} local DAILY anchors that are newer than the external
     * store's newest one, oldest first, stopping at the first failure. Sinks publish idempotently, so an unreadable sink (empty {@code latest()})
     * only causes harmless re-offers. Returns the number published.
     */
    int publishPending(AuditAnchorSink target) {
        LocalDate after = target.latest().map(AuditAnchor::anchorDate).orElse(LocalDate.of(1970, 1, 1));
        HexFormat h = HexFormat.of();
        var pending = jdbc.query("""
                SELECT anchor_date, sequence_no, entry_hash, sig FROM audit_chain_anchor
                WHERE kind = 'DAILY' AND anchor_date > ? ORDER BY anchor_date DESC LIMIT ?
                """, (rs, i) -> {
                    byte[] sig = rs.getBytes("sig");
                    return new AuditAnchor(rs.getObject("anchor_date", LocalDate.class), rs.getLong("sequence_no"),
                            h.formatHex(rs.getBytes("entry_hash")), sig != null ? h.formatHex(sig) : null);
                }, after, PENDING_LIMIT);
        pending = new java.util.ArrayList<>(pending);
        java.util.Collections.reverse(pending);
        int published = 0;
        for (AuditAnchor a : pending) {
            try {
                target.publish(a);
                published++;
            } catch (RuntimeException e) {
                log.error("Retrying the audit anchor for {} against the external sink failed; will try again", a.anchorDate(), e);
                break;
            }
        }
        return published;
    }
}
