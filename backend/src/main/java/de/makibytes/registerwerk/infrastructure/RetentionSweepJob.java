package de.makibytes.registerwerk.infrastructure;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Batched, config-driven deletion of technical/security-artifact rows that have aged past their
 * configured window — {@code registerwerk.retention.*} ({@link RetentionProperties}).
 *
 * <p>Deliberately cross-module: it operates via plain {@link JdbcTemplate} SQL against tables
 * owned by other modules (auth, wallet/orgidentity, onboarding, webhook, and Spring Modulith's
 * own event-publication outbox) rather than importing their {@code internal} repositories/entity
 * types, so it does not create a Modulith module dependency and lives in {@code infrastructure}
 * (an {@code OPEN} module) alongside {@link SchedulerLockConfig} and {@link PartitionMaintenanceJob}.
 *
 * <p>Each target is deleted in bounded batches, one batch per (auto-committing) JdbcTemplate
 * call — this method is intentionally NOT {@code @Transactional}, so a batch that is deleted
 * commits immediately rather than holding a single long transaction/lock for the whole sweep,
 * matching the "each batch in its own transaction" requirement. Compare
 * {@code idempotency.internal.IdempotencyCleanupJob}, which loads-then-deletes a whole cohort at
 * once in one transaction — acceptable there because idempotency_record's realistic 48h-old
 * backlog is small; the tables here (in particular login_attempt and webhook_delivery) can
 * plausibly accumulate much larger backlogs, so unbounded single-shot deletes are avoided.
 *
 * <p><strong>What is deliberately NOT swept here, and why</strong> (see task background:
 * "if you're not confident a table is safe to delete from, archive-only is correct"):
 * <ul>
 *   <li>{@code screening_run} / {@code screening_hit} — sanctions/AML screening decisions,
 *       including dual-control acceptance of hits. This is compliance evidence in the same
 *       family as KYC documents; there is no column here distinguishing "safe to delete" from
 *       "must retain" the way there is for e.g. app_user_action_token, so archive-only (no
 *       deletion) is the conservative choice.</li>
 *   <li>{@code corporate_action_entry} — feeds {@code SteuerbescheinigungService}'s per-investor
 *       tax-year income queries (see {@code CorporateActionEntryRepository.findSettledByInvestorAndPeriod}).
 *       This is tax-relevant financial record data, squarely inside the jurisdictions'
 *       multi-year retention obligations. Archive-only.</li>
 *   <li>{@code chain_drift_event} (even RESOLVED rows) — eWpG §16 register/on-chain discrepancy
 *       incident records. A resolved drift can still matter to a later regulatory inquiry about
 *       why the register and the chain disagreed at some point in the past; unlike a spent nonce
 *       or an expired token, "resolved" here does not mean "no longer evidentiary". Archive-only.</li>
 *   <li>{@code canton_holding_snapshot} — NOT a growing log at all: it is a durable mirror of
 *       currently-open Canton holdings, keyed by {@code contract_id}, and
 *       {@code CantonTransferSyncService} already deletes a row the moment its underlying ledger
 *       contract is archived/consumed (see {@code holdingSnapshotRepository.deleteById}). There
 *       is no stale-row accumulation here to sweep — this table is already self-pruning by
 *       design. (Correction to the plan's premise: it listed this table as a retention
 *       candidate to evaluate; on inspection it turned out not to need one at all.)</li>
 * </ul>
 */
@Component
class RetentionSweepJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweepJob.class);

    private final RetentionProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    RetentionSweepJob(RetentionProperties properties, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "retentionSweep", lockAtMostFor = "PT30M")
    public void sweep() {
        sweepLoginAttempt();
        sweepWalletBindChallenge();
        sweepOnboardingToken();
        sweepAppUserActionToken();
        sweepWebhookDelivery();
        sweepEventPublication();
        sweepChaincacheEventInbox();
    }

    /**
     * login_attempt rows that survive to be swept are, by construction, already failure-only:
     * {@code LoginAttemptLimiter.recordSuccess} deletes the row immediately on a successful
     * login, so anything left is either mid-lockout or a stale failure counter. Age alone
     * (updated_at) is therefore the correct — and only necessary — sweep criterion.
     */
    void sweepLoginAttempt() {
        RetentionProperties.Target t = properties.getLoginAttempt();
        if (!t.isEnabled()) {
            return;
        }
        int total = sweepBatched(
                "DELETE FROM login_attempt WHERE login_key IN "
                        + "(SELECT login_key FROM login_attempt WHERE updated_at < ? "
                        + "ORDER BY updated_at LIMIT ?)",
                cutoff(t), t.getBatchSize());
        logResult("login_attempt", total);
    }

    /** Only challenges that were consumed or have expired are eligible — an outstanding,
     *  unexpired challenge must never be swept out from under an in-progress bind flow. */
    void sweepWalletBindChallenge() {
        RetentionProperties.Target t = properties.getWalletBindChallenge();
        if (!t.isEnabled()) {
            return;
        }
        int total = sweepBatched(
                "DELETE FROM wallet_bind_challenge WHERE id IN "
                        + "(SELECT id FROM wallet_bind_challenge "
                        + "WHERE (used_at IS NOT NULL OR expires_at < ?) AND created_at < ? "
                        + "ORDER BY created_at LIMIT ?)",
                nowTimestamp(), cutoff(t), t.getBatchSize());
        logResult("wallet_bind_challenge", total);
    }

    /** Same shape as wallet_bind_challenge: only used or expired onboarding tokens are eligible. */
    void sweepOnboardingToken() {
        RetentionProperties.Target t = properties.getOnboardingToken();
        if (!t.isEnabled()) {
            return;
        }
        int total = sweepBatched(
                "DELETE FROM onboarding_token WHERE id IN "
                        + "(SELECT id FROM onboarding_token "
                        + "WHERE (used_at IS NOT NULL OR expires_at < ?) AND issued_at < ? "
                        + "ORDER BY issued_at LIMIT ?)",
                nowTimestamp(), cutoff(t), t.getBatchSize());
        logResult("onboarding_token", total);
    }

    /** Same shape again: only consumed or expired REGISTRATION/PASSWORD_RESET action tokens. */
    void sweepAppUserActionToken() {
        RetentionProperties.Target t = properties.getAppUserActionToken();
        if (!t.isEnabled()) {
            return;
        }
        int total = sweepBatched(
                "DELETE FROM app_user_action_token WHERE id IN "
                        + "(SELECT id FROM app_user_action_token "
                        + "WHERE (consumed_at IS NOT NULL OR expires_at < ?) AND created_at < ? "
                        + "ORDER BY created_at LIMIT ?)",
                nowTimestamp(), cutoff(t), t.getBatchSize());
        logResult("app_user_action_token", total);
    }

    /**
     * SUCCESS deliveries are terminal immediately. FAILED deliveries are only retried by
     * {@code WebhookRetryJob} up to {@code WebhookDispatchService.MAX_ATTEMPTS} (8), on a 5-minute
     * cron — so a FAILED row old enough to clear even the shortest realistic retention window has
     * certainly already exhausted its retry budget (8 attempts * 5 minutes << any sane retention
     * window). PENDING rows are never touched — a delivery still in flight must never be swept.
     */
    void sweepWebhookDelivery() {
        RetentionProperties.Target t = properties.getWebhookDelivery();
        if (!t.isEnabled()) {
            return;
        }
        int total = sweepBatched(
                "DELETE FROM webhook_delivery WHERE id IN "
                        + "(SELECT id FROM webhook_delivery "
                        + "WHERE status IN ('SUCCESS','FAILED') AND created_at < ? "
                        + "ORDER BY created_at LIMIT ?)",
                cutoff(t), t.getBatchSize());
        logResult("webhook_delivery", total);
    }

    /**
     * Spring Modulith's default {@code completion-mode} is {@code UPDATE} (confirmed against the
     * actual 2.1.0 dependency: {@code spring-modulith-events-core}'s
     * spring-configuration-metadata.json declares {@code defaultValue: "update"} for
     * {@code spring.modulith.events.completion-mode}, and this app does not override it in
     * application.yml) — completed publications are marked via {@code completion_date}, not
     * deleted, so {@code event_publication} does grow without bound exactly as the plan
     * assumed. Only rows with a non-null completion_date (i.e. already successfully processed)
     * older than the window are eligible; incomplete rows are left alone for
     * republish-outstanding-events-on-restart / retry to handle.
     */
    void sweepEventPublication() {
        RetentionProperties.Target t = properties.getEventPublication();
        if (!t.isEnabled()) {
            return;
        }
        int total = sweepBatched(
                "DELETE FROM event_publication WHERE id IN "
                        + "(SELECT id FROM event_publication "
                        + "WHERE completion_date IS NOT NULL AND completion_date < ? "
                        + "ORDER BY completion_date LIMIT ?)",
                cutoff(t), t.getBatchSize());
        logResult("event_publication", total);
    }

    /**
     * Only PROCESSED rows are ever swept — the transactional inbox's role is dedup-by-eventId
     * (see {@code ChaincacheLifecycleEventProcessor}'s class javadoc), which
     * {@code chain_event_occurrence} (the actual dedup ledger, never swept here) durably preserves
     * independently of this transport-layer inbox. QUARANTINED rows are never swept: they are the
     * evidence an operator reviews via {@code GET .../quarantined-events} before resolving a
     * quarantine, and {@code ChaincacheInboxRecoveryService.clearQuarantinedInbox} is the only
     * thing allowed to change their state. A RECEIVED row should not durably exist under this
     * method's own transactional semantics (see that class's javadoc); excluding it here is
     * defense in depth, not an expected case.
     */
    void sweepChaincacheEventInbox() {
        RetentionProperties.Target t = properties.getChaincacheEventInbox();
        if (!t.isEnabled()) {
            return;
        }
        int total = sweepBatched(
                "DELETE FROM chaincache_event_inbox WHERE id IN "
                        + "(SELECT id FROM chaincache_event_inbox "
                        + "WHERE processing_state = 'PROCESSED' AND processed_at < ? "
                        + "ORDER BY processed_at LIMIT ?)",
                cutoff(t), t.getBatchSize());
        logResult("chaincache_event_inbox", total);
    }

    private int sweepBatched(String deleteSql, Timestamp cutoff, int batchSize) {
        int total = 0;
        int affected;
        do {
            affected = jdbc.update(deleteSql, cutoff, batchSize);
            total += affected;
        } while (affected == batchSize);
        return total;
    }

    private int sweepBatched(String deleteSql, Timestamp usedCutoff, Timestamp ageCutoff, int batchSize) {
        int total = 0;
        int affected;
        do {
            affected = jdbc.update(deleteSql, usedCutoff, ageCutoff, batchSize);
            total += affected;
        } while (affected == batchSize);
        return total;
    }

    private Timestamp cutoff(RetentionProperties.Target target) {
        return Timestamp.from(Instant.now(clock).minus(target.getMaxAge()));
    }

    private Timestamp nowTimestamp() {
        return Timestamp.from(Instant.now(clock));
    }

    private void logResult(String target, int deleted) {
        if (deleted > 0) {
            log.info("Retention sweep purged {} row(s) from {}.", deleted, target);
        }
    }
}
