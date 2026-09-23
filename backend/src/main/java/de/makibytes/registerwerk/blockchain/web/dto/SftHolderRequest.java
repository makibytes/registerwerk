package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for the ERC-3525 holder endpoints ({@code POST /api/v1/deployments/{depId}/holders/...}).
 *
 * @param address EVM address (20 bytes) or Starknet felt252 account address — the service checks
 *                the exact form against the deployment's chain
 * @param reason  freeze reason written on-chain (required by {@code /holders/freeze} only; on
 *                Starknet it is stored as a felt252 short string, i.e. at most 31 ASCII characters)
 */
public record SftHolderRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{1,64}$", message = "Must be a 0x-prefixed EVM or Starknet address")
        String address,

        @Size(max = 256)
        String reason
) {}
