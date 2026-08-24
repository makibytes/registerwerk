package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Transaction-participating write and read side for a chain safety quarantine.
 *
 * <p>The immutable incident history remains in {@code chain_reorg_episode}; this table is the
 * current fail-closed snapshot. Asset scope is resolved dynamically from deployments and effect
 * provenance so every existing {@code FinalityGate} caller is frozen without duplicating scope
 * rows that could become stale while a quarantine is active.
 */
@Component
class ChainQuarantineStore implements ChainQuarantinePort {

    private final JdbcTemplate jdbcTemplate;

    ChainQuarantineStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Serializes observation/reorg decisions for one chain across application replicas. */
    void lockChain(UUID chainConfigId) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM chain_config WHERE id = ? FOR UPDATE", UUID.class, chainConfigId);
    }

    @Override
    public void requireSubmissionAllowed(UUID chainConfigId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Chain submission guard requires an active database transaction");
        }
        lockChain(chainConfigId);
        if (isActive(chainConfigId)) {
            throw new de.makibytes.registerwerk.finality.api.ChainQuarantinedException(chainConfigId);
        }
    }

    void activate(UUID chainConfigId, ReorgObservation observation) {
        QuarantineTrigger trigger = observation.severity() == ReorgObservation.ReorgSeverity.FINALITY_VIOLATION
                ? QuarantineTrigger.CONSENSUS_FINALITY_VIOLATION
                : QuarantineTrigger.UNRESOLVED_ANCESTRY;
        activate(chainConfigId, observation, trigger, null);
    }

    void activate(UUID chainConfigId, ReorgObservation observation,
            QuarantineTrigger trigger, String triggerDetail) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO chain_quarantine
                  (chain_config_id, reorg_id, severity, trigger_reason, trigger_detail,
                   observed_at, activated_at, updated_at, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (chain_config_id) DO UPDATE SET
                  reorg_id = EXCLUDED.reorg_id,
                  severity = EXCLUDED.severity,
                  trigger_reason = EXCLUDED.trigger_reason,
                  trigger_detail = EXCLUDED.trigger_detail,
                  observed_at = EXCLUDED.observed_at,
                  updated_at = EXCLUDED.updated_at,
                  active = TRUE,
                  resolved_at = NULL
                """,
                chainConfigId,
                observation.reorgId(),
                observation.severity().name(),
                trigger.name(),
                triggerDetail,
                Timestamp.from(observation.observedAt()),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    boolean isActive(UUID chainConfigId) {
        return findActive(chainConfigId).isPresent();
    }

    boolean isAssetAffected(UUID assetId) {
        if (assetId == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM chain_quarantine q
                    WHERE q.active = TRUE
                      AND (
                        EXISTS (
                            SELECT 1 FROM asset_deployment d
                            WHERE d.asset_id = ? AND d.chain_config_id = q.chain_config_id
                        )
                        OR EXISTS (
                            SELECT 1 FROM chain_effect e
                            WHERE e.asset_id = ? AND e.chain_config_id = q.chain_config_id
                        )
                      )
                )
                """, Boolean.class, assetId, assetId));
    }

    /** Clears only the active operational snapshot. The immutable reorg episode and all effect
     * resolution/acknowledgement evidence remain intact. */
    int resolve(UUID chainConfigId, Instant resolvedAt) {
        return jdbcTemplate.update("""
                UPDATE chain_quarantine
                SET active = FALSE, resolved_at = ?, updated_at = ?
                WHERE chain_config_id = ? AND active = TRUE
                """, Timestamp.from(resolvedAt), Timestamp.from(resolvedAt), chainConfigId);
    }

    @Override
    public Optional<ActiveChainQuarantine> findActive(UUID chainConfigId) {
        return jdbcTemplate.query("""
                        SELECT chain_config_id, reorg_id, severity, trigger_reason, trigger_detail,
                               observed_at, activated_at
                        FROM chain_quarantine
                        WHERE chain_config_id = ? AND active = TRUE
                        """,
                (rs, rowNum) -> new ActiveChainQuarantine(
                        rs.getObject("chain_config_id", UUID.class),
                        rs.getString("reorg_id"),
                        ReorgObservation.ReorgSeverity.valueOf(rs.getString("severity")),
                        QuarantineTrigger.valueOf(rs.getString("trigger_reason")),
                        rs.getString("trigger_detail"),
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getTimestamp("activated_at").toInstant()),
                chainConfigId).stream().findFirst();
    }
}
