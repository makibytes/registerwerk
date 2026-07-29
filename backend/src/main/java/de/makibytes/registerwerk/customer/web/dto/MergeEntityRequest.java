package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** Request payload to record an entity merge — the path entity is absorbed into {@code targetEntityId}. */
public record MergeEntityRequest(
    @NotNull UUID targetEntityId,
    @NotNull EntityMergeRecord.MergeType mergeType,
    @NotNull LocalDate effectiveDate,
    String notes
) {}
