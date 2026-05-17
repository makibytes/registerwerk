package de.makibytes.registerwerk.externalref.web.dto;

public record RegistryOverviewSummaryResponse(
    int entityCount,
    int issuerCount,
    int investorCount,
    int dualRoleCount,
    int relationshipCount
) {}
