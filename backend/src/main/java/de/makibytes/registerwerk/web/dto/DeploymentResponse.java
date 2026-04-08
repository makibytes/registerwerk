package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for an asset deployment record.
 */
public record DeploymentResponse(
    UUID id,
    UUID assetId,
    Chain chain,
    Network network,
    String contractAddress,
    AssetDeployment.DeploymentStatus deploymentStatus,
    Instant deployedAt
) {}
