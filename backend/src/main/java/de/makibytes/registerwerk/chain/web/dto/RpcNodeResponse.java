package de.makibytes.registerwerk.chain.web.dto;

import de.makibytes.registerwerk.chain.api.RpcNode;

import java.time.Instant;
import java.util.Map;
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
        boolean syncing,
        RpcNode.NodeKind kind,
        String managementUrl,
        String remoteChainKey,
        Map<String, Object> capabilities
) {}
