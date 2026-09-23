package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * The UserOperation a customer wants sponsored, as prepared by their wallet (viem's
 * {@code getPaymasterData} hook). Integers are decimal or 0x-hex strings (uint256-safe).
 * The voucher signs exactly these values, so any later change to them invalidates it.
 */
public record GasSponsorshipVoucherRequest(
    @NotNull UUID deploymentId,
    @NotBlank @Pattern(regexp = "^0x[0-9a-fA-F]{40}$") String sender,
    @NotBlank @Pattern(regexp = QUANTITY) String nonce,
    @Pattern(regexp = "^(0x)?([0-9a-fA-F]{2})*$") String initCode,
    @NotBlank @Pattern(regexp = "^0x([0-9a-fA-F]{2})+$") String callData,
    @NotBlank @Pattern(regexp = QUANTITY) String callGasLimit,
    @NotBlank @Pattern(regexp = QUANTITY) String verificationGasLimit,
    @NotBlank @Pattern(regexp = QUANTITY) String preVerificationGas,
    @NotBlank @Pattern(regexp = QUANTITY) String maxFeePerGas,
    @NotBlank @Pattern(regexp = QUANTITY) String maxPriorityFeePerGas,
    @NotBlank @Pattern(regexp = QUANTITY) String paymasterVerificationGasLimit,
    @NotBlank @Pattern(regexp = QUANTITY) String paymasterPostOpGasLimit
) {
    public static final String QUANTITY = "^(0x[0-9a-fA-F]{1,64}|[0-9]{1,78})$";
}
