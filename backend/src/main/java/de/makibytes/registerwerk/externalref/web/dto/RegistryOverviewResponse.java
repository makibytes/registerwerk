package de.makibytes.registerwerk.externalref.web.dto;

import java.time.Instant;
import java.util.List;

public record RegistryOverviewResponse(
    Instant generatedAt,
    RegistryOverviewSummaryResponse summary,
    List<RegistryEntityNodeResponse> entities,
    List<RegistryRelationshipResponse> relationships
) {}
