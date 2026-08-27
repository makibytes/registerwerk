package de.makibytes.registerwerk.indexer.web.dto;

import de.makibytes.registerwerk.indexer.internal.ChainDriftEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ChainDriftEventResponse(
        UUID id,
        UUID assetId,
        UUID deploymentId,
        String walletAddress,
        BigDecimal dbBalance,
        BigDecimal onchainBalance,
        BigDecimal delta,
        String severity,
        String status,
        Instant firstDetectedAt,
        Instant detectedAt,
        Instant resolvedAt,
        UUID resolvedBy,
        String resolutionNotes
) {
    public static ChainDriftEventResponse from(ChainDriftEvent e) {
        return new ChainDriftEventResponse(
                e.getId(), e.getAssetId(), e.getDeploymentId(), e.getWalletAddress(),
                e.getDbBalance(), e.getOnchainBalance(), e.getDelta(),
                e.getSeverity().name(), e.getStatus().name(),
                e.getFirstDetectedAt(), e.getDetectedAt(), e.getResolvedAt(), e.getResolvedBy(), e.getResolutionNotes()
        );
    }
}
