package de.makibytes.registerwerk.web.dto;

import java.util.List;

/**
 * Public descriptor of a jurisdiction's KYC document requirements.
 * Used by the frontend to render a checklist before the user even uploads documents.
 */
public record JurisdictionRequirementResponse(
    String jurisdiction,
    String displayName,
    String regulator,
    String applicableLaw,
    List<DocumentRequirementResponse> requirements
) {
    public record DocumentRequirementResponse(
        String documentType,
        boolean mandatory,
        String localName,
        String description,
        Long maxAgeDays
    ) {}
}
