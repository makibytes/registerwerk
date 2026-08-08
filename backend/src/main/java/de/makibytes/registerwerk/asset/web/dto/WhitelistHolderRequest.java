package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WhitelistHolderRequest(@NotNull UUID deploymentId) {}
