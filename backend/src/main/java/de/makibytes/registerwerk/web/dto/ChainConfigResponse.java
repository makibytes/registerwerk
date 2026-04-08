package de.makibytes.registerwerk.web.dto;

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
        boolean enabled
) {}
