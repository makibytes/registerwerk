package de.makibytes.registerwerk.chain.web.dto;

import java.util.UUID;

/**
 * Response DTO for a chain configuration entry.
 */
public record ChainConfigResponse(
        UUID id,
        String identifier,
        String displayName,
        String chainType,
        String networkType,
        Long chainId,
        String blockExplorerUrl,
        String finalityModel,
        Integer avgBlockSeconds,
        String finalitySource,
        boolean enabled
) {}
