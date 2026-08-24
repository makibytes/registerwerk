package de.makibytes.registerwerk.indexer.web.dto;

import de.makibytes.registerwerk.indexer.api.IndexerState;

import java.time.Instant;
import java.util.UUID;

public record IndexerStateResponse(
        UUID id,
        UUID chainConfigId,
        String indexerType,
        String status,
        Long lastSyncedBlock,
        Long lastFinalBlock,
        String lastSyncedSignature,
        Instant lastSyncedAt,
        int consecutiveErrors,
        String lastError
) {
    public static IndexerStateResponse from(IndexerState s) {
        return new IndexerStateResponse(
                s.getId(), s.getChainConfigId(), s.getIndexerType().name(), s.getStatus().name(),
                s.getLastSyncedBlock(), s.getLastFinalBlock(), s.getLastSyncedSignature(),
                s.getLastSyncedAt(), s.getConsecutiveErrors(), s.getLastError()
        );
    }
}
