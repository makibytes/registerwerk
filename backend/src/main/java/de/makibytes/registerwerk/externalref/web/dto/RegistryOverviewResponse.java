package de.makibytes.registerwerk.externalref.web.dto;

import de.makibytes.registerwerk.customer.web.dto.RegistryEntityNodeResponse;
import de.makibytes.registerwerk.customer.web.dto.RegistryRelationshipResponse;
import java.time.Instant;
import java.util.List;

public record RegistryOverviewResponse(
    Instant generatedAt,
    RegistryOverviewSummaryResponse summary,
    List<RegistryEntityNodeResponse> entities,
    List<RegistryRelationshipResponse> relationships
) {}
