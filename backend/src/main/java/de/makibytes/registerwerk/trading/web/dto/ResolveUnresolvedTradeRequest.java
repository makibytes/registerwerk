package de.makibytes.registerwerk.trading.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Operator resolution of a PAYMENT_UNRESOLVED trade; {@code legalBasis} is mandatory (4-eyes action). */
public record ResolveUnresolvedTradeRequest(@NotBlank @Size(max = 500) String legalBasis, @Size(max = 2000) String note) {}
