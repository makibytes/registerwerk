package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/vault-requests/{requestId}/fulfill}.
 *
 * @param navAtFulfill  The NAV per share applied at fulfillment time. Must match the latest struck NAV.
 */
public record FulfillVaultRequestBody(
        @NotNull @Positive
        BigDecimal navAtFulfill
) {}
