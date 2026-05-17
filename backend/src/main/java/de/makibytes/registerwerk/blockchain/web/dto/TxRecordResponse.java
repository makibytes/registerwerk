package de.makibytes.registerwerk.blockchain.web.dto;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransaction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read-only view of a {@link BlockchainTransaction} record. */
public record TxRecordResponse(
        UUID id,
        String txHash,
        String status,
        String methodName,
        String chain,
        String network,
        String contractAddress,
        UUID deploymentId,
        UUID assetId,
        String actorName,
        String actorRole,
        Map<String, Object> params,
        Long gasUsed,
        Long blockNumber,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
    public static TxRecordResponse from(BlockchainTransaction tx) {
        return new TxRecordResponse(
                tx.getId(), tx.getTxHash(), tx.getStatus().name(),
                tx.getMethodName(), tx.getChain(), tx.getNetwork(),
                tx.getContractAddress(), tx.getDeploymentId(), tx.getAssetId(),
                tx.getActorName(), tx.getActorRole(), tx.getParams(),
                tx.getGasUsed(), tx.getBlockNumber(), tx.getErrorMessage(),
                tx.getCreatedAt(), tx.getCompletedAt());
    }
}
