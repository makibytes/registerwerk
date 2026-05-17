package de.makibytes.registerwerk.blockchain.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read-only view of a blockchain transaction record, safe for cross-module use. */
public record BlockchainTransactionView(
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
) {}
