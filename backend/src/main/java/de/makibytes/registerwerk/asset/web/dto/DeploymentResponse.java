package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;

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
