package de.makibytes.registerwerk.asset.web.dto;

/**
 * A signed {@code EwpgPaymaster} voucher. {@code paymasterData} is
 * {@code policyId ‖ validUntil ‖ validAfter ‖ maxFeePerGasCap ‖ signature} and goes into the
 * UserOperation unchanged, together with {@code paymaster} and the two paymaster gas limits.
 */
public record GasSponsorshipVoucherResponse(
    String paymaster,
    String paymasterData,
    String paymasterVerificationGasLimit,
    String paymasterPostOpGasLimit,
    long chainId,
    String policyId,
    long validUntil,
    long validAfter,
    String maxFeePerGasCap,
    String maxCostWei
) {}
