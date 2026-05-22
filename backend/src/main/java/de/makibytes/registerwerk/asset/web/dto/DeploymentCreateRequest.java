package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload to initiate a new asset deployment.
 */
public record DeploymentCreateRequest(
    @NotNull Chain chain,
    @NotNull Network network
) {}
