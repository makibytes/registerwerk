package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditAnchor;
import de.makibytes.registerwerk.audit.api.AuditAnchorSink;
import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies the SHA-256 hash chain of the audit_event table (see AuditEventRecorder,
 * which appends to it). Exposes a /actuator/health contributor so broken chains surface
 * as DOWN. Recomputes every row's entry_hash from its stored content using the same
 * {@link AuditCanonicalJson} the write path uses, so both a broken prev_hash pointer
 * (row reordering/deletion — though the WORM trigger already blocks that at the SQL
 * level) and silent content tampering (a payload edited in place, bypassing the trigger
 * via a privileged connection) are detected.
 */
@Component
public class AuditChainVerificationService implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerificationService.class);
    private static final int BATCH_SIZE = 2000;

    record VerificationResult(boolean valid, long rowsChecked, Long firstBrokenSeq, Instant checkedAt, String reason) {
        VerificationResult(boolean valid, long rowsChecked, Long firstBrokenSeq, Instant checkedAt) {
            this(valid, rowsChecked, firstBrokenSeq, checkedAt, null);
        }
    }

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbc;
    private final AuditCanonicalJson canonicalJson;
    private final Optional<SigningKeyProvider> signingKeyProvider;
    private final Optional<AuditAnchorSink> anchorSink;
    private final AtomicReference<VerificationResult> lastResult =
            new AtomicReference<>(new VerificationResult(true, 0, null, Instant.now()));

    /**
     * Highest {@code sequence_no} at or below which a NULL {@code entry_hash} is tolerated as
     * a known historical gap (rows written before the chain-append fix shipped) rather than
     * treated as a broken chain. Defaults to 0 — i.e. no tolerance — because a deployment that
     * never suffered that historical gap (every deployment going forward) should never see a
     * NULL entry_hash at all; only a deployment that knows it has a real legacy gap should set
     * this to that gap's actual last affected sequence_no. Previously this tolerance was
     * unbounded (any NULL hash, at any point in the chain, reset continuity), which could not
     * distinguish a genuine historical gap from a maliciously or accidentally inserted unchained
     * row appearing today.
     */
    @Value("${registerwerk.audit.legacy-hash-gap-max-sequence-no:0}")
    private long legacyHashGapMaxSequenceNo;

    AuditChainVerificationService(AuditEventRepository repository, JdbcTemplate jdbc, AuditCanonicalJson canonicalJson,
                                  Optional<SigningKeyProvider> signingKeyProvider, Optional<AuditAnchorSink> anchorSink,
                                  MeterRegistry meterRegistry) {
        this.anchorSink = anchorSink;
        this.repository = repository;
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
        this.signingKeyProvider = signingKeyProvider;

        // Gauge functions are called by Micrometer at scrape time, so these always reflect the
        // current lastResult/key age — no separate push step needed.
        Gauge.builder("registerwerk_audit_chain_valid", lastResult, r -> r.get().valid() ? 1.0 : 0.0)
                .description("1 if the audit hash chain's last verification was valid, 0 if broken")
                .register(meterRegistry);

        Gauge.builder("registerwerk_audit_chain_tip_age_seconds", this, AuditChainVerificationService::tipAgeSeconds)
                .description("Seconds since the audit chain tip was last advanced")
                .register(meterRegistry);

        signingKeyProvider.ifPresent(provider ->
                Gauge.builder("registerwerk_audit_signing_key_age_seconds", provider,
                                p -> Duration.between(p.createdAt(), Instant.now()).getSeconds())
                        .description("Seconds since the active audit-chain signing key was created/last rotated")
                        .register(meterRegistry));
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
                .withDetail("reason", String.valueOf(r.reason()))
                .withDetail("rowsChecked", r.rowsChecked())
                .withDetail("checkedAt", r.checkedAt())
                .build();
    }

    /** Result of verifying one page: how far continuity got, and where/why it broke (if it did). */

    /**
     * Nightly full-chain verification, paged to avoid loading the whole table into memory.
     * Uses {@link Slice} rather than {@link Page} — only {@code isLast()} is needed, and a
     * {@code Page} would force an extra {@code COUNT(*)} per batch across the whole table.
     */
    @SchedulerLock(name = "auditChainVerification", lockAtMostFor = "PT2H")
    @Scheduled(cron = "0 30 3 * * *")
    public void verify() {
        runFullVerification();
    }

    /**
     * Runs the same full-chain scan as the nightly {@link #verify()} job, on demand — e.g. from
     * an operator-triggered "Verify now" action, rather than only ever
     * running unattended at 03:30 with results visible solely via {@code /actuator/health}.
     */
    public VerificationResult verifyNow() {
        return runFullVerification();
    }

    /** The most recently computed result, without triggering a new scan. */
    public VerificationResult lastResult() {
        return lastResult.get();
    }

    private volatile long tipAgeCachedAt;
    private volatile double tipAgeCached;

    private double tipAgeSeconds() {
        long now = System.currentTimeMillis();
        if (now - tipAgeCachedAt > 15_000) {
            try {
                Double v = jdbc.queryForObject(
                        "SELECT EXTRACT(EPOCH FROM (now() - updated_at))::float8 FROM audit_chain_tip WHERE id = TRUE",
                        Double.class);
                tipAgeCached = v != null ? v : 0.0;
            } catch (RuntimeException e) {
                tipAgeCached = -1;
            }
            tipAgeCachedAt = now;
        }
        return tipAgeCached;
    }

    /** Mutable scan state carried across pages. */
    private static final class Scan {
        byte[] expectedPrev;
        boolean sawAny;
        boolean afterLegacyGap;
        byte[] lastHash;
        Long lastSeq;
        long count;
    }

    private record Break(Long seq, String reason) {}

    private VerificationResult runFullVerification() {
        log.info("Starting audit chain verification...");
        Scan scan = new Scan();
        Long signingFrom = jdbc.queryForObject("SELECT signing_from_seq FROM audit_chain_meta WHERE id = TRUE", Long.class);
        byte[] archivedAnchor = archivedAnchorHash();
        Break broken = null;

        for (int page = 0; broken == null; page++) {
            Slice<AuditEvent> batch = repository.findAllSliceBy(
                    PageRequest.of(page, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "sequenceNo")));
            broken = verifyPage(batch.getContent(), scan, signingFrom, archivedAnchor);
            if (batch.isLast()) {
                break;
            }
        }
        if (broken == null) {
            broken = verifyTailAndAnchors(scan);
        }
        VerificationResult result;
        if (broken != null) {
            log.error("Audit chain BROKEN at sequence_no={}: {}", broken.seq(), broken.reason());
            result = new VerificationResult(false, scan.count, broken.seq(), Instant.now(), broken.reason());
        } else {
            log.info("Audit chain verification complete: {} rows verified, chain intact.", scan.count);
            result = new VerificationResult(true, scan.count, null, Instant.now());
        }
        lastResult.set(result);
        return result;
    }

    private byte[] archivedAnchorHash() {
        var rows = jdbc.queryForList(
                "SELECT entry_hash FROM audit_chain_anchor WHERE kind = 'ARCHIVED_UP_TO' ORDER BY sequence_no DESC LIMIT 1",
                byte[].class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Head/tail truncation, dropped partitions and rolled-back tips: compare with tip, anchors, sink. */
    private Break verifyTailAndAnchors(Scan scan) {
        var tip = jdbc.queryForMap("SELECT entry_hash, sequence_no FROM audit_chain_tip WHERE id = TRUE");
        byte[] tipHash = (byte[]) tip.get("entry_hash");
        Number tipSeq = (Number) tip.get("sequence_no");
        if (tipHash != null && !Arrays.equals(tipHash, scan.lastHash)) {
            return new Break(tipSeq != null ? tipSeq.longValue() : scan.lastSeq,
                    "chain tail does not match audit_chain_tip (rows removed or tip rewritten)");
        }
        if (tipSeq != null && !tipSeq.equals(scan.lastSeq)) {
            return new Break(tipSeq.longValue(), "last verified sequence_no differs from audit_chain_tip");
        }
        for (var a : jdbc.queryForList("SELECT sequence_no, entry_hash, sig FROM audit_chain_anchor WHERE kind = 'DAILY'")) {
            long seq = ((Number) a.get("sequence_no")).longValue();
            Break b = checkAnchor(seq, (byte[]) a.get("entry_hash"), (byte[]) a.get("sig"), "anchor");
            if (b != null) {
                return b;
            }
        }
        if (anchorSink.isPresent()) {
            Optional<AuditAnchor> ext = anchorSink.get().latest();
            if (ext.isPresent()) {
                var h = java.util.HexFormat.of();
                return checkAnchor(ext.get().sequenceNo(), h.parseHex(ext.get().entryHashHex()),
                        ext.get().sigHex() != null ? h.parseHex(ext.get().sigHex()) : null, "external anchor");
            }
        }
        return null;
    }

    private Break checkAnchor(long seq, byte[] hash, byte[] sig, String what) {
        var rows = jdbc.queryForList("SELECT entry_hash FROM audit_event WHERE sequence_no = ?", byte[].class, seq);
        if (rows.isEmpty()) {
            return new Break(seq, what + " references sequence_no " + seq + " which is missing (tail truncated or partition dropped)");
        }
        if (!Arrays.equals(rows.get(0), hash)) {
            return new Break(seq, what + " hash differs from the row at sequence_no " + seq + " (history rewritten)");
        }
        if (sig != null && signingKeyProvider.isPresent()
                && !signingKeyProvider.get().verify(AuditAnchorService.anchorDigest(seq, hash), sig)) {
            return new Break(seq, what + " signature invalid at sequence_no " + seq);
        }
        return null;
    }

    /** Verifies one page; returns the first break or null, carrying continuity in {@code scan}. */
    private Break verifyPage(java.util.List<AuditEvent> rows, Scan scan, Long signingFrom, byte[] archivedAnchor) {
        for (AuditEvent e : rows) {
            scan.count++;
            Long seq = e.getSequenceNo();
            byte[] prevHash = e.getPrevHash();
            byte[] entryHash = e.getEntryHash();

            if (entryHash == null) {
                if (seq == null || seq > legacyHashGapMaxSequenceNo) {
                    return new Break(seq, "NULL entry_hash beyond the configured legacy-gap cutoff "
                            + "(registerwerk.audit.legacy-hash-gap-max-sequence-no="
                            + legacyHashGapMaxSequenceNo + ") — possible unchained/out-of-band insert");
                }
                // Known historical gap: continuity resets here rather than failing the whole chain.
                scan.expectedPrev = null;
                scan.afterLegacyGap = true;
                scan.sawAny = true;
                continue;
            }

            if (!scan.sawAny && !scan.afterLegacyGap) {
                // Head check: the first row must be the genesis or follow the recorded archive point.
                if (prevHash != null && (archivedAnchor == null || !Arrays.equals(prevHash, archivedAnchor))) {
                    return new Break(seq, "first row is not the chain genesis (head truncated or partition dropped)");
                }
            } else if (scan.expectedPrev != null && !Arrays.equals(prevHash, scan.expectedPrev)) {
                return new Break(seq, "prev_hash pointer mismatch");
            }
            scan.sawAny = true;

            byte[] recomputed;
            try {
                recomputed = sha256(prevHash, canonicalJson.canonicalize(e), seq);
            } catch (IllegalArgumentException ex) {
                return new Break(seq, ex.getMessage());
            }
            if (!Arrays.equals(recomputed, entryHash)) {
                return new Break(seq, "content hash mismatch (tampered row?)");
            }

            // From the signing watermark on, a missing signature is a break (a rewritten suffix with
            // NULL signatures must not verify). Before it (signing not yet enabled) NULL is expected.
            boolean mustBeSigned = signingFrom != null && seq != null && seq >= signingFrom;
            if (mustBeSigned && e.getEntrySig() == null) {
                return new Break(seq, "entry_sig missing at or after the signing watermark (" + signingFrom + ")");
            }
            if (e.getEntrySig() != null) {
                if (signingKeyProvider.isEmpty()) {
                    if (mustBeSigned) {
                        return new Break(seq, "signed rows exist but no signing key provider is configured");
                    }
                } else if (!signingKeyProvider.get().verify(entryHash, e.getEntrySig())) {
                    return new Break(seq, "entry_sig verification failed (tampered or wrong signing key)");
                }
            }

            scan.expectedPrev = entryHash;
            scan.lastHash = entryHash;
            scan.lastSeq = seq;
        }
        return null;
    }

    /** Returns the current hash-chain tip (for anchoring / external attestation). */
    public byte[] currentChainTip() {
        return jdbc.queryForObject("SELECT entry_hash FROM audit_chain_tip WHERE id = TRUE", byte[].class);
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
