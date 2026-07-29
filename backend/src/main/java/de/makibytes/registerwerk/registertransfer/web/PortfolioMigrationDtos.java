package de.makibytes.registerwerk.registertransfer.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request payloads for the portfolio-migration endpoints. */
public final class PortfolioMigrationDtos {

    private PortfolioMigrationDtos() {}

    public record MigrationInitiateRequest(
            @NotNull UUID holderId,
            @NotBlank String reason
    ) {}

    public record SetDestinationRequest(
            String destinationRegistrarName,
            String destinationRegistrarIdentifier,
            @NotBlank String destinationWalletAddress
    ) {}

    public record OnchainTransferRequest(
            @NotBlank String txHash
    ) {}

    public record MigrationCancelRequest(
            @NotBlank String reason
    ) {}
}
