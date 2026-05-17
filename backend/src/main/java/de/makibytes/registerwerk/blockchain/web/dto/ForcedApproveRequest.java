package de.makibytes.registerwerk.blockchain.web.dto;

import java.math.BigInteger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST .../forced-approve}.
 *
 * @param owner      Token owner granting allowance/approval
 * @param spender    Spender/operator that receives approval
 * @param value      Allowance amount (ERC-20), tokenId (ERC-721), or approval flag value (ERC-1155: >0 approve)
 * @param legalBasis Mandatory legal reference for the regulatory override
 */
public record ForcedApproveRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String owner,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String spender,

        @NotNull
        @Positive
        BigInteger value,

        @NotBlank(message = "legalBasis is required for forced approvals")
        String legalBasis
) {}
