package de.makibytes.registerwerk.finality.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read-shape of a persisted {@code chain_effect} row, handed to a {@link ChainEffectCompensator}
 * when the dispatcher asks it to undo the effect.
 */
public record ChainEffectRecord(
        UUID id,
        UUID chainConfigId,
        long blockNumber,
        String blockHash,
        String txHash,
        Integer logIndex,
        String moduleName,
        String effectType,
        String entityType,
        UUID entityId,
        UUID assetId,
        CompensationCategory category,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        UUID auditEventId,
        UUID correlationId,
        String status,
        int attemptCount,
        Instant recordedAt) {
}
