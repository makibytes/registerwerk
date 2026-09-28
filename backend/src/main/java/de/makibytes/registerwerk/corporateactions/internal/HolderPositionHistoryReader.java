package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.deployment.api.HolderKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads {@code asset_holder_position_history} (V13, trigger-maintained): each holder row's state
 * as of an instant — the latest history row with {@code valid_from < cutoff} (T3-06).
 */
@Component
class HolderPositionHistoryReader {

    /**
     * @param backfilled  written by the V13 backfill rather than the trigger — its values are the
     *                    state at {@code recordedAt}, back-dated to the row's creation
     */
    record HistoricPosition(UUID holderId, UUID investorId, String walletAddress, HolderKind holderKind,
                            BigDecimal nominalAmount, Instant removedAt, boolean backfilled, Instant recordedAt) {

        /** Held at the cut-off: not removed at or before it. */
        boolean activeAt(Instant cutoff) {
            return removedAt == null || removedAt.isAfter(cutoff);
        }
    }

    private final JdbcTemplate jdbc;

    HolderPositionHistoryReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Holder id → its state as of {@code cutoff}. Holders created after the cut-off are absent. */
    Map<UUID, HistoricPosition> positionsAsOf(UUID assetId, Instant cutoff) {
        Map<UUID, HistoricPosition> result = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (holder_id) holder_id, investor_id, wallet_address, holder_kind,
                       nominal_amount, removed_at, backfilled, recorded_at
                FROM asset_holder_position_history
                WHERE asset_id = ? AND valid_from < ?
                ORDER BY holder_id, valid_from DESC, id DESC
                """, rs -> {
            Timestamp removed = rs.getTimestamp("removed_at");
            UUID holderId = rs.getObject("holder_id", UUID.class);
            result.put(holderId, new HistoricPosition(holderId, rs.getObject("investor_id", UUID.class),
                    rs.getString("wallet_address"), HolderKind.valueOf(rs.getString("holder_kind")),
                    rs.getBigDecimal("nominal_amount"), removed != null ? removed.toInstant() : null,
                    rs.getBoolean("backfilled"), rs.getTimestamp("recorded_at").toInstant()));
        }, assetId, Timestamp.from(cutoff));
        return result;
    }
}
