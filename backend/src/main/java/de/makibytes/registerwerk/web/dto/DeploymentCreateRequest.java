package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload to initiate a new asset deployment.
 */
public record DeploymentCreateRequest(
    @NotNull Chain chain,
    @NotNull Network network
) {}
