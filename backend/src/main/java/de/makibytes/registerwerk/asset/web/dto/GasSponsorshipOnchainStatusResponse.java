package de.makibytes.registerwerk.asset.web.dto;

import java.util.UUID;

/**
 * On-chain state of a policy on {@code EwpgPaymaster}, shown next to the DB row. Amounts are wei
 * as decimal strings. {@code status} is one of {@code NO_POLICY}, {@code NO_CHAIN},
 * {@code NOT_CONFIGURED} (no paymaster address is set for the chain; {@code configured=false}),
 * {@code OK} or {@code READ_ERROR} ({@code error} carries the read failure instead of failing the
 * whole panel).
 */
public record GasSponsorshipOnchainStatusResponse(
    String status,
    UUID policyRowId,
    boolean configured,
    String paymaster,
    String chainIdentifier,
    String policyId,
    boolean registered,
    boolean active,
    String funder,
    String signer,
    String balanceWei,
    String reservedWei,
    String orgBudgetCapWei,
    String error
) {}
