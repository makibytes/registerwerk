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
import java.util.UUID;

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
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Persisted verdict as seen by this pod, cached 15 s (7B-04); invalidated by every local run/ack. */
    record Snapshot(UUID id, VerificationResult latest, boolean broken) {
        static final Snapshot UNKNOWN = new Snapshot(null, null, false);
        boolean unknown() { return latest == null; }
    }
    /** The verdict is persisted in its own transaction: callers (AuditApi) may be read-only. */
    private final org.springframework.transaction.support.TransactionTemplate persistTx;
    private volatile Snapshot cached;
    private volatile long cachedAt;

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
                                  MeterRegistry meterRegistry,
                                  org.springframework.context.ApplicationEventPublisher events,
                                  org.springframework.transaction.PlatformTransactionManager txManager) {
        this.events = events;
        this.persistTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.persistTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.anchorSink = anchorSink;
        this.repository = repository;
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
        this.signingKeyProvider = signingKeyProvider;

        // Gauge functions are called by Micrometer at scrape time, so these always reflect the
        // current lastResult/key age — no separate push step needed.
        Gauge.builder("registerwerk_audit_chain_valid", this, svc -> {
                    Snapshot sn = svc.snapshot();
                    return sn.unknown() ? -1.0 : (sn.broken() ? 0.0 : 1.0);
                })
                .description("1 valid, 0 broken (until a later valid run AND an ack), -1 unknown (no run recorded)")
                .register(meterRegistry);
        Gauge.builder("registerwerk_audit_chain_last_verified_timestamp_seconds", this, svc -> {
                    Snapshot sn = svc.snapshot();
                    return sn.unknown() || sn.latest().checkedAt() == null ? 0.0 : sn.latest().checkedAt().getEpochSecond();
                })
                .description("Unix time of the latest persisted audit-chain verification run (any pod)")
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
        Snapshot sn = snapshot();
        if (sn.unknown()) {
            return Health.unknown().withDetail("reason", "no audit chain verification has been recorded yet").build();
        }
        VerificationResult r = sn.latest();
        if (sn.broken()) {
            return Health.down()
                    .withDetail("firstBrokenSequenceNo", String.valueOf(r.firstBrokenSeq()))
                    .withDetail("reason", String.valueOf(r.reason()))
                    .withDetail("rowsChecked", r.rowsChecked())
                    .withDetail("checkedAt", r.checkedAt())
                    .withDetail("note", "stays DOWN until a later valid run AND a dual-control acknowledgement")
                    .build();
        }
        return Health.up()
                .withDetail("rowsChecked", r.rowsChecked())
                .withDetail("checkedAt", r.checkedAt())
                .build();
    }

    Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Snapshot sn = cached;
        if (sn == null || now - cachedAt > 15_000) {
            try {
                sn = loadSnapshot();
            } catch (RuntimeException e) {
                log.warn("Could not read audit chain verification state: {}", e.toString());
                if (sn == null) {
                    sn = Snapshot.UNKNOWN;
                }
            }
            cached = sn;
            cachedAt = now;
        }
        return sn;
    }

    private Snapshot loadSnapshot() {
        var rows = jdbc.queryForList(
                "SELECT id, ran_at, valid, rows_checked, first_broken_seq, reason FROM audit_chain_verification "
                        + "ORDER BY ran_at DESC, id LIMIT 1");
        if (rows.isEmpty()) {
            return Snapshot.UNKNOWN;
        }
        var row = rows.get(0);
        boolean latestValid = Boolean.TRUE.equals(row.get("valid"));
        Boolean unacked = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM audit_chain_verification v WHERE NOT v.valid "
                        + "AND NOT EXISTS (SELECT 1 FROM audit_chain_verification_ack a WHERE a.verification_id = v.id))",
                Boolean.class);
        Number first = (Number) row.get("first_broken_seq");
        Object ranAt = row.get("ran_at");
        Instant ran = ranAt instanceof java.sql.Timestamp t ? t.toInstant()
                : ((java.time.OffsetDateTime) ranAt).toInstant();
        VerificationResult r = new VerificationResult(latestValid, ((Number) row.get("rows_checked")).longValue(),
                first == null ? null : first.longValue(), ran, (String) row.get("reason"));
        return new Snapshot((UUID) row.get("id"), r, !latestValid || Boolean.TRUE.equals(unacked));
    }

    void invalidate() {
        cached = null;
    }

    /** Acknowledges a broken verdict (dual control enforced at the controller) (7B-04). */
    @org.springframework.transaction.annotation.Transactional
    public void acknowledge(UUID verificationId, UUID actorId, String role, String note) {
        var rows = jdbc.queryForList("SELECT valid FROM audit_chain_verification WHERE id = ?", verificationId);
        if (rows.isEmpty()) {
            throw new de.makibytes.registerwerk.shared.EntityNotFoundException("AuditChainVerification", verificationId);
        }
        if (Boolean.TRUE.equals(rows.get(0).get("valid"))) {
            throw new de.makibytes.registerwerk.shared.InvalidStateTransitionException(
                    "Only a broken verification can be acknowledged");
        }
        int n = jdbc.update("INSERT INTO audit_chain_verification_ack (verification_id, acked_by, note) VALUES (?, ?, ?) "
                + "ON CONFLICT (verification_id) DO NOTHING", verificationId, actorId, note);
        if (n == 0) {
            throw new de.makibytes.registerwerk.shared.InvalidStateTransitionException("Already acknowledged");
        }
        events.publishEvent(new de.makibytes.registerwerk.audit.events.AuditChainVerificationAckedEvent(
                verificationId, actorId, role, note));
        invalidate();
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
        runFullVerification("NIGHTLY");
    }

    /**
     * Runs the same full-chain scan as the nightly {@link #verify()} job, on demand — e.g. from
     * an operator-triggered "Verify now" action, rather than only ever
     * running unattended at 03:30 with results visible solely via {@code /actuator/health}.
     */
    public VerificationResult verifyNow() {
        return runFullVerification("ON_DEMAND");
    }

    /** The latest persisted result (any pod), without triggering a new scan; null when none exists. */
    public VerificationResult lastResult() {
        return snapshot().latest();
    }

    /** Effective state: broken until a later valid run AND an acknowledgement exist. */
    public String status() {
        Snapshot sn = snapshot();
        return sn.unknown() ? "UNKNOWN" : (sn.broken() ? "BROKEN" : "VALID");
    }

    /**
     * Id the acknowledge endpoint needs: the newest broken run still lacking an acknowledgement, else the
     * latest run (a clean latest run with an unacknowledged earlier break must still be acknowledgeable).
     */
    public UUID latestVerificationId() {
        try {
            var ids = jdbc.queryForList(
                    "SELECT v.id FROM audit_chain_verification v WHERE NOT v.valid AND NOT EXISTS "
                            + "(SELECT 1 FROM audit_chain_verification_ack a WHERE a.verification_id = v.id) "
                            + "ORDER BY v.ran_at DESC, v.id LIMIT 1", UUID.class);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read unacknowledged audit verification: {}", e.toString());
        }
        return snapshot().id();
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

    private VerificationResult runFullVerification(String source) {
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
            result = new VerificationResult(false, scan.count, broken.seq(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), broken.reason());
        } else {
            log.info("Audit chain verification complete: {} rows verified, chain intact.", scan.count);
            result = new VerificationResult(true, scan.count, null, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
        persistTx.executeWithoutResult(st -> jdbc.update(
                "INSERT INTO audit_chain_verification (ran_at, valid, rows_checked, first_broken_seq, reason, source) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", java.sql.Timestamp.from(result.checkedAt()), result.valid(),
                result.rowsChecked(), result.firstBrokenSeq(), result.reason(), source));
        invalidate();
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
