package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.ExternalReferenceSubjectType;

import java.time.Instant;
import java.util.UUID;

public record CompanyExternalReferenceValueResponse(
        ExternalReferenceSubjectType subjectType,
        UUID subjectId,
        String externalId,
        Instant updatedAt) {}
