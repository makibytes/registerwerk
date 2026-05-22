package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.ExternalReferenceSubjectType;

import java.time.Instant;
import java.util.UUID;

public record CompanyExternalReferenceValueResponse(
        ExternalReferenceSubjectType subjectType,
        UUID subjectId,
        String externalId,
        Instant updatedAt) {}
