package de.makibytes.registerwerk.corporateactions.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body of the operator's settlement confirmation (Wave 0b C6).
 *
 * @param payoutDigest the {@code payoutDigest} of the computed amounts the operator reviewed; when present and
 *                     different from the current digest the confirmation is refused (409)
 */
public record ConfirmSettlementRequest(@Size(max = 64) String payoutDigest) {
}
