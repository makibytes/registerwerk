package de.makibytes.registerwerk.indexer.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Track A7: Enforces the eWpG §16 / KryptoFAV §6 "registry is canonical" principle.
 * Periodically compares each ISSUED asset holder's registered nominal amount with
 * the balance reconstructed from indexed token transfers, and persists divergences
 * to chain_drift_event.
 *
 * <p>Design notes:
 * <ul>
 *   <li><strong>Keyset pagination</strong> over asset_holder (ORDER BY ah.id) keeps
 *       memory bounded on large assets; batch size is configurable.</li>
 *   <li><strong>Deduplication:</strong> one OPEN drift event per (deployment, wallet) —
 *       re-detections refresh balances/severity instead of inserting a new row every
 *       run; a new row is only created after the previous event was resolved.</li>
 *   <li><strong>Confirm-on-reconfirmation:</strong> a divergence seen for the first time is
 *       inserted as an unconfirmed "candidate" — invisible to the operator "Open" queue and the
 *       open-count gauge (see {@code ChainDriftEventRepository}'s confirmed-filtered finders).
 *       It only becomes a real, human-visible OPEN case once the <em>same</em> divergence is
 *       still present on a later run. This exists because a fresh boot (or any restart) can
 *       leave token_transfer briefly behind the registry while Chaincache's durable event
 *       subscription is still catching up — every holder touched during that window looks like
 *       100% drift for one detection cycle. A genuine divergence (a bug, a bridge failure, an
 *       operator error) persists across runs and gets confirmed normally; a catch-up artifact
 *       resolves itself within one interval and is auto-cleared below before a human ever sees
 *       it. Once confirmed, a case is never auto-resolved — only {@code ChainDriftService#resolve}
 *       can close it, per the eWpG canonical-registry requirement that a real divergence needs a
 *       documented human decision.</li>
 *   <li><strong>No method-level transaction:</strong> rows are independent; a single
 *       failed insert must not roll back the findings of the whole scan — the control
 *       would otherwise fail exactly when it detects something.</li>
 *   <li>severity is a plain VARCHAR column — no Postgres enum cast.</li>
 * </ul>
 */
@Component
class ChainDriftDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(ChainDriftDetectionJob.class);
    private static final BigDecimal CRITICAL_THRESHOLD_PCT = new BigDecimal("0.01"); // 1%

    /** EVM chains — the only ones whose addresses are safe to case-fold (EIP-55 checksums vs. The
     *  Graph's lowercase indexing). Solana (base58) and Stellar (StrKey base32) are
     *  case-SENSITIVE encodings where folding can (in principle) collide two distinct addresses. */
    private static final java.util.Set<String> EVM_CHAIN_SET = java.util.Set.of(
            "ETHEREUM", "POLYGON", "BASE", "FHENIX", "INCO", "ARBITRUM", "AVALANCHE", "OPTIMISM");

    /** SQL IN-list literal derived from {@link #EVM_CHAIN_SET} so the two never drift apart. */
    private static final String EVM_CHAINS = EVM_CHAIN_SET.stream()
            .map(chain -> "'" + chain + "'")
            .collect(java.util.stream.Collectors.joining(","));

    private final JdbcTemplate jdbc;
    private final int batchSize;

    ChainDriftDetectionJob(JdbcTemplate jdbc,
                           @Value("${registerwerk.drift.batch-size:500}") int batchSize,
                           MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.batchSize = batchSize;
        // Live query at scrape time rather than a value cached between this job's own 15-minute
        // runs — chain_drift_event is small and status is a simple equality filter, so a COUNT
        // here is cheap. confirmed = true excludes same-run "candidates" that haven't yet
        // survived a second detection pass — see processRow's confirm-on-reconfirmation flow.
        Gauge.builder("registerwerk_chain_drift_open_total", jdbc,
                        j -> j.queryForObject(
                                "SELECT count(*) FROM chain_drift_event WHERE status = 'OPEN' AND confirmed = true",
                                Long.class))
                .description("Count of currently OPEN and confirmed chain_drift_event rows (registry vs. on-chain balance divergence)")
                .register(meterRegistry);
    }

    @SchedulerLock(name = "chainDriftDetection", lockAtMostFor = "PT10M")
    @Scheduled(fixedDelayString = "${registerwerk.drift.check-interval-ms:900000}",
               initialDelayString = "${registerwerk.drift.initial-delay-ms:95000}")
    public void checkDrift() {
        int driftCount = 0;
        int scanned = 0;
        UUID lastId = null;

        while (true) {
            List<Map<String, Object>> batch = loadBatch(lastId);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : batch) {
                lastId = toUuid(row.get("holder_id"));
                scanned++;
                if (processRow(row)) {
                    driftCount++;
                }
            }
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (driftCount > 0) {
            log.error("Chain drift detection: {} divergences among {} holders. Check chain_drift_event table.",
                    driftCount, scanned);
        } else {
            log.debug("Chain drift detection: {} holders scanned, no divergence.", scanned);
        }
    }

    private List<Map<String, Object>> loadBatch(UUID afterId) {
        // Keyset pagination: strictly increasing ah.id, bounded batch. Two query
        // variants — Postgres cannot infer the type of a NULL parameter in
        // "(? IS NULL OR ah.id > ?)" and would reject the prepared statement.
        String keysetPredicate = afterId == null ? "" : "AND ah.id > ? ";
        String sql = """
            SELECT
                ah.id              AS holder_id,
                ah.asset_id,
                ah.wallet_address,
                ah.nominal_amount  AS db_balance,
                COALESCE(
                    (SELECT SUM(
                        CASE WHEN (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(tt.to_address) ELSE tt.to_address END)
                                  = (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(ah.wallet_address) ELSE ah.wallet_address END)
                             THEN tt.amount ELSE -tt.amount END)
                     FROM token_transfer tt
                     -- Case-fold only for EVM chains: The Graph indexes lowercase addresses while
                     -- holder rows may store EIP-55 checksummed ones, so a case-sensitive join
                     -- there would silently report zero drift for every holder. Solana (base58)
                     -- and Stellar (StrKey) addresses are compared exactly.
                     WHERE tt.finality_status <> 'ORPHANED'
                       AND (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(tt.contract_address) ELSE tt.contract_address END)
                         = (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(ad.contract_address) ELSE ad.contract_address END)
                       AND (
                            (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(tt.from_address) ELSE tt.from_address END)
                                = (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(ah.wallet_address) ELSE ah.wallet_address END)
                            OR (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(tt.to_address) ELSE tt.to_address END)
                                = (CASE WHEN ad.chain IN (""" + EVM_CHAINS + """
) THEN LOWER(ah.wallet_address) ELSE ah.wallet_address END)
                           )
                    ), ah.nominal_amount
                ) AS indexed_balance,
                -- Net units this holder gained or lost through the SIMULATED venue, which
                -- settles the register off-chain with no token_transfer. Those legitimately
                -- move the register ahead of the chain, so they are added to the on-chain
                -- reconstruction before comparing — otherwise every simulated sale looks like
                -- drift. Real venues settle on-chain and are deliberately not netted out.
                COALESCE((SELECT SUM(te.executed_quantity) FROM trade_execution te
                          WHERE te.buyer_holder_id = ah.id
                            AND te.venue_code = 'SIMULATED'
                            AND te.settlement_status = 'SETTLED'), 0)
                - COALESCE((SELECT SUM(te.executed_quantity) FROM trade_execution te
                          WHERE te.seller_holder_id = ah.id
                            AND te.venue_code = 'SIMULATED'
                            AND te.settlement_status = 'SETTLED'), 0) AS net_simulated,
                ad.id              AS deployment_id,
                ad.chain           AS chain
            FROM asset_holder ah
            JOIN asset a ON a.id = ah.asset_id AND a.status = 'ISSUED'
            JOIN asset_deployment ad ON ad.asset_id = a.id
                AND ad.deployment_status = 'CONFIRMED'
                AND ad.contract_address IS NOT NULL
            WHERE ah.nominal_amount IS NOT NULL
            """ + keysetPredicate + """
            ORDER BY ah.id
            LIMIT ?
            """;
        return afterId == null
                ? jdbc.queryForList(sql, batchSize)
                : jdbc.queryForList(sql, afterId, batchSize);
    }

    /** @return true if a divergence was recorded, refreshed, or confirmed */
    private boolean processRow(Map<String, Object> row) {
        BigDecimal dbBalance = toBigDecimal(row.get("db_balance"));
        BigDecimal indexedBalance = toBigDecimal(row.get("indexed_balance"));
        if (dbBalance == null || indexedBalance == null) {
            return false;
        }

        UUID assetId = toUuid(row.get("asset_id"));
        UUID deploymentId = toUuid(row.get("deployment_id"));
        String wallet = (String) row.get("wallet_address");
        String chain = (String) row.get("chain");
        // Case-fold the wallet-address match only for EVM chains — Solana (base58) and Stellar
        // (StrKey) addresses are case-sensitive.
        boolean isEvm = chain != null && EVM_CHAIN_SET.contains(chain);
        String walletMatchSql = isEvm ? "LOWER(wallet_address) = LOWER(?)" : "wallet_address = ?";

        // Off-chain SIMULATED-venue settlements legitimately move the register ahead of the
        // chain; fold them into the expected on-chain balance so they are not flagged as drift.
        BigDecimal netSimulated = toBigDecimal(row.get("net_simulated"));
        BigDecimal effectiveIndexed = netSimulated != null ? indexedBalance.add(netSimulated) : indexedBalance;
        BigDecimal delta = effectiveIndexed.subtract(dbBalance).abs();
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            autoResolveUnconfirmedCandidate(deploymentId, wallet, walletMatchSql);
            return false;
        }

        String severity = dbBalance.compareTo(BigDecimal.ZERO) > 0
                && delta.divide(dbBalance, 10, RoundingMode.HALF_UP)
                        .compareTo(CRITICAL_THRESHOLD_PCT) >= 0
                ? "CRITICAL" : "WARNING";

        try {
            // One OPEN event per (deployment, wallet). Three outcomes, tried in order:
            //   1. An unconfirmed candidate from a prior run is still diverging now — promote it
            //      (this run is the second, independent sighting that confirms it).
            //   2. An already-confirmed case is still diverging — refresh its balances/severity
            //      silently, same as before this change.
            //   3. No existing OPEN row at all — insert a brand-new, unconfirmed candidate; it
            //      only becomes visible to an operator if step 1 fires for it on a later run.
            // The recorded on-chain balance is the reconstruction after netting out pending
            // off-chain settlement, so (db_balance − onchain_balance) equals the flagged delta.
            // NOTE: the space after "AND" below is a required `\s` escape, not a stray edit —
            // Java text blocks strip trailing whitespace from every line, so a plain trailing
            // space here is silently dropped and "AND" glues directly onto walletMatchSql
            // (e.g. "ANDLOWER(...)"), which Postgres rejects outright as invalid syntax. That
            // exact bug shipped for a time: every refresh attempt threw, was swallowed by the
            // catch below, and no OPEN drift event was ever actually refreshed or logged past
            // its first detection — confirmed by reproducing the literal concatenated SQL string.
            int promoted = jdbc.update("""
                UPDATE chain_drift_event
                SET db_balance = ?, onchain_balance = ?, severity = ?, detected_at = now(), confirmed = true
                WHERE deployment_id = ? AND\s""" + walletMatchSql + """
                 AND status = 'OPEN' AND confirmed = false
                """, dbBalance, effectiveIndexed, severity, deploymentId, wallet);
            if (promoted > 0) {
                log.warn("Chain drift CONFIRMED [{}] asset={} wallet={} db={} indexed={} delta={} "
                                + "— persisted across two consecutive scans.",
                        severity, assetId, wallet, dbBalance, effectiveIndexed, delta);
                return true;
            }

            int refreshed = jdbc.update("""
                UPDATE chain_drift_event
                SET db_balance = ?, onchain_balance = ?, severity = ?, detected_at = now()
                WHERE deployment_id = ? AND\s""" + walletMatchSql + """
                 AND status = 'OPEN' AND confirmed = true
                """, dbBalance, effectiveIndexed, severity, deploymentId, wallet);
            if (refreshed == 0) {
                jdbc.update("""
                    INSERT INTO chain_drift_event
                      (asset_id, deployment_id, wallet_address, db_balance, onchain_balance, severity)
                    VALUES (?,?,?,?,?,?)
                    """, assetId, deploymentId, wallet, dbBalance, effectiveIndexed, severity);
                log.debug("Chain drift candidate [{}] asset={} wallet={} db={} indexed={} delta={} "
                                + "— awaiting reconfirmation on the next scan before it is surfaced.",
                        severity, assetId, wallet, dbBalance, effectiveIndexed, delta);
            }
            return true;
        } catch (Exception e) {
            log.error("Chain drift: failed to persist event for wallet {}: {}", wallet, e.getMessage());
            return false;
        }
    }

    /** Silently closes a same-run "candidate" (never confirmed) whose divergence has disappeared
     *  on a later scan — most likely the transient indexer/chain-stream catch-up window this
     *  confirm-on-reconfirmation flow exists to filter out, not genuine drift. A confirmed case
     *  is deliberately left untouched here: only {@code ChainDriftService#resolve} may close one,
     *  since a human already had to look at it. */
    private void autoResolveUnconfirmedCandidate(UUID deploymentId, String wallet, String walletMatchSql) {
        try {
            int cleared = jdbc.update("""
                UPDATE chain_drift_event
                SET status = 'RESOLVED', resolved_at = now(),
                    resolution_notes = 'Auto-resolved: divergence was no longer present on a later scan, before ever being confirmed.'
                WHERE deployment_id = ? AND\s""" + walletMatchSql + """
                 AND status = 'OPEN' AND confirmed = false
                """, deploymentId, wallet);
            if (cleared > 0) {
                log.debug("Chain drift: auto-resolved an unconfirmed candidate for deployment={} wallet={} "
                        + "— balances now agree.", deploymentId, wallet);
            }
        } catch (Exception e) {
            log.error("Chain drift: failed to auto-resolve stale candidate for wallet {}: {}", wallet, e.getMessage());
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            // Exact conversion — NUMERIC(38,18) balances must not pass through double.
            return new BigDecimal(n.toString());
        }
        return null;
    }

    private static UUID toUuid(Object v) {
        if (v instanceof UUID u) {
            return u;
        }
        if (v != null) {
            return UUID.fromString(v.toString());
        }
        return null;
    }
}
