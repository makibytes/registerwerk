package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.ExternalReferenceSubjectType;

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
