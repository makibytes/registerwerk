package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.ExternalReferenceSubjectType;

import java.time.Instant;
import java.util.UUID;

public record CompanyExternalReferenceLookupResponse(
        ExternalReferenceSubjectType subjectType,
        UUID subjectId,
        String externalId,
        String displayName,
        String contextLabel,
        UUID relatedAssetId,
        Instant updatedAt) {}
