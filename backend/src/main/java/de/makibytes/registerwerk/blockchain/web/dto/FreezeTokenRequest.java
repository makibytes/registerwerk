package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/tokens/{tokenId}/freeze}.
 *
 * @param reason  Short human-readable reason for the freeze (logged in on-chain event).
 */
public record FreezeTokenRequest(
        @NotBlank
        String reason
) {}
