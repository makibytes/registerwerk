package de.makibytes.registerwerk.registertransfer.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request payloads for the portfolio-migration endpoints. */
public final class PortfolioMigrationDtos {

    private PortfolioMigrationDtos() {}

    public record MigrationInitiateRequest(
            @NotNull UUID holderId,
            @NotBlank String reason,
            // T3-07 (C-05b): required when the entry carries third-party rights or disposal
            // restrictions — reference of the beneficiary's consent to the migration.
            String beneficiaryConsentRef
    ) {}

    public record SetDestinationRequest(
            String destinationRegistrarName,
            String destinationRegistrarIdentifier,
            @NotBlank String destinationWalletAddress
    ) {}

    public record OnchainTransferRequest(
            @NotBlank String txHash,
            // T3-17: required when the asset has no indexed deployment to verify the tx against
            // (off-chain register, Solana/Canton until Phase 4).
            String operatorAttestation,
            // T3-07 (C-05b): may be supplied here when rights/restrictions arose after initiation.
            String beneficiaryConsentRef
    ) {}

    public record MigrationCancelRequest(
            @NotBlank String reason
    ) {}
}
