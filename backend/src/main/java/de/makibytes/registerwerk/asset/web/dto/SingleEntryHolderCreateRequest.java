package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload for adding a holder in single entry (Einzeleintragung).
 * Carries the §17(2) eWpG attributes and the consumer flag (§19 statement
 * obligation). The pseudonymous holder reference is generated server-side.
 */
public record SingleEntryHolderCreateRequest(
    @NotNull UUID investorId,
    @NotBlank String walletAddress,
    BigDecimal nominalAmount,
    boolean isConsumer,
    String thirdPartyRights,
    String disposalRestrictions,
    String legalCapacityNote
) {}
