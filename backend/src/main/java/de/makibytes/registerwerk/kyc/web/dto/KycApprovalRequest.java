package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Entity-level KYC approval. {@code expiryDate} defaults to the maximum validity and may not exceed
 * {@code registerwerk.kyc.max-validity-months}. {@code jurisdiction} selects the document checklist (default: derived
 * from the registration country). {@code overrideNote} (REGISTRY_ADMIN only) is required to approve with an
 * incomplete checklist or on the senior-managing-official fallback.
 */
public record KycApprovalRequest(
    @Future LocalDate expiryDate,
    Jurisdiction jurisdiction,
    @Size(max = 2000) String overrideNote
) {}
