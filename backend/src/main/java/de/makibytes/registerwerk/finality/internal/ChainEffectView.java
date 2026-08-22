package de.makibytes.registerwerk.finality.internal;

import java.time.Instant;
import java.util.UUID;

/** Read-shape of a {@link ChainEffect} row for the operator "unresolved compensation" queue — see
 *  {@link FinalityPolicyOverrideView} for why this is a separate public record rather than
 *  exposing the entity. */
public record ChainEffectView(
        UUID id, UUID chainConfigId, long blockNumber, String txHash, String moduleName,
        String effectType, String entityType, UUID entityId, UUID assetId, String category,
        String status, int attemptCount, String resolutionDetail, Instant recordedAt,
        UUID acknowledgedBy, Instant acknowledgedAt, String acknowledgeReason) {

    static ChainEffectView of(ChainEffect c) {
        return new ChainEffectView(c.getId(), c.getChainConfigId(), c.getBlockNumber(), c.getTxHash(),
                c.getModuleName(), c.getEffectType(), c.getEntityType(), c.getEntityId(), c.getAssetId(),
                c.getCategory().name(), c.getStatus().name(), c.getAttemptCount(), c.getResolutionDetail(),
                c.getRecordedAt(), c.getAcknowledgedBy(), c.getAcknowledgedAt(), c.getAcknowledgeReason());
    }
}
