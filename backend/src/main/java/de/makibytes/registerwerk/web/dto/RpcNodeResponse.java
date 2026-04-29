package de.makibytes.registerwerk.web.dto;

import java.time.Instant;
import java.util.UUID;

public record RpcNodeResponse(
        UUID id,
        UUID chainConfigId,
        String chainIdentifier,
        String url,
        String label,
        boolean enabled,
        boolean exclusive,
        Long latestBlockNumber,
        Instant blockLastAdvancedAt,
        Instant lastCheckedAt,
        Instant lastSuccessAt,
        boolean healthy,
        int consecutiveFailures,
        Integer lagFromBest,
        boolean syncing
) {}
