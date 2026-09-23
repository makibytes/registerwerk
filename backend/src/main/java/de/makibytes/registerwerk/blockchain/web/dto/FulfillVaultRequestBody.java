package de.makibytes.registerwerk.blockchain.web.dto;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/vault-requests/{requestId}/fulfill}.
 * The body is optional: fulfilment always settles at the NAV currently struck on-chain, and the
 * executed NAV is recorded from the fulfilment event once confirmed.
 *
 * @param navAtFulfill Deprecated and ignored — accepted for one release so existing clients keep
 *                     working; a value is only logged as a deprecation warning.
 */
public record FulfillVaultRequestBody(
        @Deprecated
        BigDecimal navAtFulfill
) {}
