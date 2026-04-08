package de.makibytes.registerwerk.web.dto.erc3643;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for registering a required claim topic on a T-REX suite.
 */
public record AddClaimTopicRequest(
    @NotNull Long topic,
    String label
) {}
