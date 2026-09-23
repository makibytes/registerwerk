package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/vault-requests/{requestId}/force-cancel}.
 *
 * @param to         destination of the request's escrow (underlying for a deposit request,
 *                   escrowed shares for a redeem request), named per case — never the vault itself
 * @param legalBasis court order / authority reference recorded on-chain in {@code ForcedRequestCancelled}
 */
public record ForceCancelVaultRequestBody(
        @NotBlank @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "must be an EVM address")
        String to,
        @NotBlank @Size(max = 1000)
        String legalBasis
) {}
