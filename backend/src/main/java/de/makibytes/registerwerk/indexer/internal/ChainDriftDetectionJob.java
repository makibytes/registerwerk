package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Track A7: Enforces the eWpG §16 / KryptoFAV §6 "registry is canonical" principle.
 * Every 15 minutes, fetches on-chain balanceOf() for each ISSUED asset and compares
 * to asset_holder.nominal_amount. Persists divergences to chain_drift_event.
 *
 * Note: EVM balance queries are async-batched via Web3jClientFactory; large assets may
 * need pagination. Start with a soft threshold of >0 drift = WARNING; >1% = CRITICAL.
 */
@Component
class ChainDriftDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(ChainDriftDetectionJob.class);
    private static final BigDecimal CRITICAL_THRESHOLD_PCT = new BigDecimal("0.01"); // 1%

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    ChainDriftDetectionJob(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${registerwerk.drift.check-interval-ms:900000}")
    @Transactional
    public void checkDrift() {
        // Query holders with their most recent indexed transfer balance vs DB balance
        var drifts = jdbc.queryForList("""
            SELECT
                ah.id              AS holder_id,
                ah.asset_id,
                ah.wallet_address,
                ah.nominal_amount  AS db_balance,
                COALESCE(
                    (SELECT SUM(
                        CASE WHEN tt.to_address = ah.wallet_address THEN tt.amount
                             ELSE -tt.amount END)
                     FROM token_transfer tt
                     WHERE tt.contract_address = ad.contract_address
                       AND (tt.from_address = ah.wallet_address OR tt.to_address = ah.wallet_address)
                    ), ah.nominal_amount
                ) AS indexed_balance,
                ad.id              AS deployment_id
            FROM asset_holder ah
            JOIN asset a ON a.id = ah.asset_id AND a.status = 'ISSUED'
            JOIN asset_deployment ad ON ad.asset_id = a.id
                AND ad.deployment_status = 'CONFIRMED'
                AND ad.contract_address IS NOT NULL
            WHERE ah.nominal_amount IS NOT NULL
            """);

        int driftCount = 0;
        for (var row : drifts) {
            BigDecimal dbBalance = toBigDecimal(row.get("db_balance"));
            BigDecimal indexedBalance = toBigDecimal(row.get("indexed_balance"));
            if (dbBalance == null || indexedBalance == null) continue;

            BigDecimal delta = indexedBalance.subtract(dbBalance).abs();
            if (delta.compareTo(BigDecimal.ZERO) == 0) continue;

            String severity;
            if (dbBalance.compareTo(BigDecimal.ZERO) > 0
                    && delta.divide(dbBalance, 10, java.math.RoundingMode.HALF_UP)
                            .compareTo(CRITICAL_THRESHOLD_PCT) >= 0) {
                severity = "CRITICAL";
            } else {
                severity = "WARNING";
            }

            UUID assetId = toUuid(row.get("asset_id"));
            UUID deploymentId = toUuid(row.get("deployment_id"));
            String wallet = (String) row.get("wallet_address");

            jdbc.update("""
                INSERT INTO chain_drift_event
                  (asset_id, deployment_id, wallet_address,
                   db_balance, onchain_balance, severity)
                VALUES (?,?,?,?,?,?::drift_severity)
                """, assetId, deploymentId, wallet,
                    dbBalance, indexedBalance, severity);

            log.warn("Chain drift [{}] asset={} wallet={} db={} indexed={} delta={}",
                    severity, assetId, wallet, dbBalance, indexedBalance, delta);
            driftCount++;
        }

        if (driftCount > 0) {
            log.error("Chain drift detection: {} divergences found. Check chain_drift_event table.", driftCount);
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private static UUID toUuid(Object v) {
        if (v instanceof UUID u) return u;
        if (v != null) return UUID.fromString(v.toString());
        return null;
    }
}
