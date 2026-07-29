package de.makibytes.registerwerk.audit.internal;

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

    record VerificationResult(boolean valid, long rowsChecked, Long firstBrokenSeq, Instant checkedAt) {}

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbc;
    private final AuditCanonicalJson canonicalJson;
    private final Optional<SigningKeyProvider> signingKeyProvider;
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
                                  Optional<SigningKeyProvider> signingKeyProvider, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
        this.signingKeyProvider = signingKeyProvider;

        // Gauge functions are called by Micrometer at scrape time, so these always reflect the
        // current lastResult/key age — no separate push step needed.
        Gauge.builder("registerwerk_audit_chain_valid", lastResult, r -> r.get().valid() ? 1.0 : 0.0)
                .description("1 if the audit hash chain's last verification was valid, 0 if broken")
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
                .withDetail("rowsChecked", r.rowsChecked())
                .withDetail("checkedAt", r.checkedAt())
                .build();
    }

    /** Result of verifying one page: how far continuity got, and where/why it broke (if it did). */
    private record PageOutcome(long rowsChecked, byte[] continuedPrevHash, Long brokenSeq, String brokenReason) {}

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

    private VerificationResult runFullVerification() {
        log.info("Starting audit chain verification...");
        long count = 0;
        byte[] expectedPrevHash = null;

        for (int page = 0; ; page++) {
            Slice<AuditEvent> batch = repository.findAllSliceBy(
                    PageRequest.of(page, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "sequenceNo")));

            PageOutcome outcome = verifyPage(batch.getContent(), expectedPrevHash);
            count += outcome.rowsChecked();
            if (outcome.brokenSeq() != null) {
                log.error("Audit chain BROKEN at sequence_no={}: {}", outcome.brokenSeq(), outcome.brokenReason());
                VerificationResult result = new VerificationResult(false, count, outcome.brokenSeq(), Instant.now());
                lastResult.set(result);
                return result;
            }
            expectedPrevHash = outcome.continuedPrevHash();

            if (batch.isLast()) {
                break;
            }
        }

        log.info("Audit chain verification complete: {} rows verified, chain intact.", count);
        VerificationResult result = new VerificationResult(true, count, null, Instant.now());
        lastResult.set(result);
        return result;
    }

    /** Verifies one page in isolation; stops at the first mismatch instead of scanning past it. */
    private PageOutcome verifyPage(java.util.List<AuditEvent> rows, byte[] expectedPrevHash) {
        long checked = 0;
        for (AuditEvent e : rows) {
            checked++;
            Long seq = e.getSequenceNo();
            byte[] prevHash = e.getPrevHash();
            byte[] entryHash = e.getEntryHash();

            if (entryHash == null) {
                if (seq == null || seq > legacyHashGapMaxSequenceNo) {
                    return new PageOutcome(checked, null, seq,
                            "NULL entry_hash beyond the configured legacy-gap cutoff "
                                    + "(registerwerk.audit.legacy-hash-gap-max-sequence-no="
                                    + legacyHashGapMaxSequenceNo + ") — possible unchained/out-of-band insert");
                }
                // Known historical gap (rows written before the chain-append fix shipped),
                // at or before the configured cutoff — continuity resets here rather than
                // failing the whole chain.
                expectedPrevHash = null;
                continue;
            }

            if (expectedPrevHash != null && !Arrays.equals(prevHash, expectedPrevHash)) {
                return new PageOutcome(checked, null, seq, "prev_hash pointer mismatch");
            }

            byte[] recomputed = sha256(prevHash,
                    canonicalJson.canonicalize(e.getEventType(), e.getSubjectType(), e.getSubjectId(), e.getPayload()),
                    seq);
            if (!Arrays.equals(recomputed, entryHash)) {
                return new PageOutcome(checked, null, seq, "content hash mismatch (tampered payload?)");
            }

            // Only attempted when both a signing key and a stored signature exist — rows
            // written before signing was opted into have no entry_sig, and a provider that's
            // since been removed/reconfigured can't retroactively re-verify old rows; neither
            // case weakens the mandatory hash-chain check above, which always runs.
            if (e.getEntrySig() != null && signingKeyProvider.isPresent()
                    && !signingKeyProvider.get().verify(entryHash, e.getEntrySig())) {
                return new PageOutcome(checked, null, seq, "entry_sig verification failed (tampered or wrong signing key)");
            }

            expectedPrevHash = entryHash;
        }
        return new PageOutcome(checked, expectedPrevHash, null, null);
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
