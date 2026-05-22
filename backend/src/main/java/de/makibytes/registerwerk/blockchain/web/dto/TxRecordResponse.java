package de.makibytes.registerwerk.blockchain.web.dto;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read-only view of a blockchain transaction record returned to API callers. */
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
    public static TxRecordResponse from(BlockchainTransactionView tx) {
        return new TxRecordResponse(
                tx.id(), tx.txHash(), tx.status(),
                tx.methodName(), tx.chain(), tx.network(),
                tx.contractAddress(), tx.deploymentId(), tx.assetId(),
                tx.actorName(), tx.actorRole(), tx.params(),
                tx.gasUsed(), tx.blockNumber(), tx.errorMessage(),
                tx.createdAt(), tx.completedAt());
    }
}
