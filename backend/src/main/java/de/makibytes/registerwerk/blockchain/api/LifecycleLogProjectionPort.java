package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.finality.api.FinalityLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Indexer-owned projection operations invoked by the blockchain transport module. */
public interface LifecycleLogProjectionPort {

    UUID create(TransferProjection projection);

    void promote(UUID transferId, FinalityLevel finality);

    void orphan(UUID transferId);

    record TransferProjection(UUID assetId, UUID deploymentId, UUID chainConfigId,
            String contractAddress, String fromAddress, String toAddress, BigDecimal tokenId,
            BigDecimal amount, EventType eventType, String transactionHash, long blockNumber,
            int logIndex, Instant occurredAt, String explorerTransactionUrl,
            Map<String, Object> rawData, FinalityLevel finality, String blockHash) {
        public TransferProjection {
            rawData = Map.copyOf(rawData);
        }
    }

    enum EventType { MINT, TRANSFER, BURN }
}

