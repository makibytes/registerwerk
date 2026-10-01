package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.events.KycRejectionCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code reason} is the operator's internal free text (audit trail and operator UI only).
 * {@code customerReasonCode} is the fixed category shown to the customer; defaults to CONTACT_SUPPORT.
 */
public record KycRejectionRequest(@NotBlank @Size(max = 2000) String reason, KycRejectionCategory customerReasonCode) {}
