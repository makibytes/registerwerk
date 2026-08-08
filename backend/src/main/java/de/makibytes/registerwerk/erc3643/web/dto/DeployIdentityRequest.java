package de.makibytes.registerwerk.erc3643.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DeployIdentityRequest(@NotNull UUID chainConfigId) {}
