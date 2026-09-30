package de.makibytes.registerwerk.blockchain.web.dto;

import de.makibytes.registerwerk.shared.api.StrictAmount;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigInteger;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/tokens/{tokenId}/forced-value-transfer}.
 *
 * <p>Legal basis: eWpG §24 Berichtigung. Transfers value units between two tokens in the same slot.
 *
 * @param toTokenId   Destination token identifier.
 * @param value       Number of value units to transfer.
 * @param legalBasis  Mandatory reference to the legal authority (e.g. "BaFin Bescheid Az. 2025-001").
 */
public record ForcedValueTransferRequest(
        @NotNull @Positive
        @JsonDeserialize(using = StrictAmount.BigIntegerAmount.class) BigInteger toTokenId,

        @NotNull @Positive
        @JsonDeserialize(using = StrictAmount.BigIntegerAmount.class) BigInteger value,

        @NotBlank(message = "legalBasis is required for forced transfers")
        String legalBasis
) {}
