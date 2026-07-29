package de.makibytes.registerwerk.trading.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Evidence of payment the buyer supplies to settle a PENDING trade — a
 * stablecoin tx hash, a SEPA transfer reference, etc., depending on the trade's payment option.
 * Required rather than optional: previously settling required nothing beyond the HTTP call
 * itself.
 */
public record SettleTradeRequest(@NotBlank String paymentReference) {}
