package de.makibytes.registerwerk.web.dto.registry;

public record RegistryOverviewSummaryResponse(
    int entityCount,
    int issuerCount,
    int investorCount,
    int dualRoleCount,
    int relationshipCount
) {}
