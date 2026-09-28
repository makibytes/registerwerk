package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /assets/{id}/redeem} (T3-01). Non-bond assets have no corporate action to
 * point at, so the operator states the legal basis (e.g. eWpG §26 Einziehung) and a reference
 * (resolution, court order, CA id); both go into the audit trail with the second approver.
 */
public record RedeemRequest(
        @NotBlank @Size(max = 500) String legalBasis,
        @NotBlank @Size(max = 200) String reference
) {
}
