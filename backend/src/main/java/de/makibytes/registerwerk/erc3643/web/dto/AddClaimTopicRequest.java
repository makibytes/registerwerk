package de.makibytes.registerwerk.erc3643.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for registering a required claim topic on a T-REX suite.
 */
public record AddClaimTopicRequest(
    @NotNull Long topic,
    String label
) {}
