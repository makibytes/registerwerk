package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request payload to record an entity merge - the path entity is absorbed into {@code targetEntityId}.
 * {@code reason} is mandatory; {@code evidenceDocumentId} (a KYC/register document on file) is recorded
 * on the audit event.
 */
public record MergeEntityRequest(
    @NotNull UUID targetEntityId,
    @NotNull EntityMergeRecord.MergeType mergeType,
    @NotNull LocalDate effectiveDate,
    String notes,
    @NotBlank @Size(max = 2000) String reason,
    UUID evidenceDocumentId
) {}
