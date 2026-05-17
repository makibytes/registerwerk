package de.makibytes.registerwerk.erc3643.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a full T-REX suite record.
 */
public record Erc3643SuiteResponse(
    UUID id,
    UUID assetDeploymentId,
    String tokenAddress,
    String identityRegistryAddress,
    String identityRegistryStorage,
    String complianceAddress,
    String claimTopicsRegistry,
    String trustedIssuersRegistry,
    boolean isConfidential,
    Instant createdAt
) {}
