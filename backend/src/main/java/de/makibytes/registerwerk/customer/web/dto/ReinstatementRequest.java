package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reinstatement of a CLOSED/DISSOLVED entity (T6-12): both fields are mandatory and persisted on
 * the audit event. {@code legalReference} names the legal basis (court decision, registry
 * entry, contract/ticket id).
 */
public record ReinstatementRequest(
        @NotBlank @Size(max = 2000) String reason,
        @NotBlank @Size(max = 500) String legalReference) {}
