package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.BlockIdentity;
import de.makibytes.registerwerk.finality.api.ReorgEnvelopeConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Durable, transaction-participating idempotency claim for one upstream reorg occurrence. */
@Component
class ReorgEpisodeStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    ReorgEpisodeStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    boolean claim(UUID chainConfigId, ReorgObservation observation) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO chain_reorg_episode
                  (chain_config_id, reorg_id, schema_version, severity,
                   common_ancestor_number, common_ancestor_hash, episode, observed_at, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
                ON CONFLICT (chain_config_id, reorg_id) DO NOTHING
                """,
                chainConfigId,
                observation.reorgId(),
                observation.schemaVersion(),
                observation.severity().name(),
                observation.commonAncestor() == null ? null : observation.commonAncestor().blockNumber(),
                observation.commonAncestor() == null ? null : observation.commonAncestor().blockHash(),
                json(observation),
                Timestamp.from(observation.observedAt()),
                Timestamp.from(Instant.now()));
        if (inserted == 1) {
            return true;
        }
        if (replayStatus(chainConfigId, observation) == ReplayStatus.EXACT) {
            return false;
        }
        throw new ReorgEnvelopeConflictException("reorgId=" + observation.reorgId()
                + " was reused for a semantically different envelope on chainConfigId=" + chainConfigId);
    }

    ReplayStatus replayStatus(UUID chainConfigId, ReorgObservation observation) {
        return jdbcTemplate.query("""
                        SELECT episode::text FROM chain_reorg_episode
                        WHERE chain_config_id = ? AND reorg_id = ?
                        """,
                (rs, rowNum) -> objectMapper.readValue(rs.getString(1), ReorgObservation.class),
                chainConfigId, observation.reorgId()).stream().findFirst()
                .map(stored -> normalize(stored).equals(normalize(observation))
                        ? ReplayStatus.EXACT : ReplayStatus.CONFLICT)
                .orElse(ReplayStatus.MISSING);
    }

    enum ReplayStatus { MISSING, EXACT, CONFLICT }

    private static ReorgObservation normalize(ReorgObservation observation) {
        return new ReorgObservation(
                observation.schemaVersion(), observation.reorgId(), observation.severity(),
                normalize(observation.commonAncestor()),
                observation.orphanedLineage().stream().map(ReorgEpisodeStore::normalize).toList(),
                observation.replacementLineage().stream().map(ReorgEpisodeStore::normalize).toList(),
                observation.observedAt());
    }

    private static ReorgObservation.BlockReference normalize(ReorgObservation.BlockReference block) {
        return block == null ? null : new ReorgObservation.BlockReference(
                block.blockNumber(), BlockIdentity.normalize(block.blockHash()),
                BlockIdentity.normalize(block.parentHash()), block.finality());
    }

    private String json(ReorgObservation observation) {
        return objectMapper.writeValueAsString(observation);
    }
}
