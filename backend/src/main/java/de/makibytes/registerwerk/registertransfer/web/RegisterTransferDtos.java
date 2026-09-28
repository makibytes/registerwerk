package de.makibytes.registerwerk.registertransfer.web;

import de.makibytes.registerwerk.registertransfer.api.InspectionLegalBasis;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request payloads for the register inspection / transfer endpoints. */
public final class RegisterTransferDtos {

    private RegisterTransferDtos() {}

    public record InspectionSubmitRequest(
            @NotNull UUID assetId,
            /** Ignored - the requester entity is derived from the JWT (T3-11). Kept for client compatibility. */
            UUID requesterEntityId,
            @NotBlank String requesterName,
            String requesterEmail,
            @NotNull InspectionLegalBasis legalBasis,
            String statedInterest
    ) {}

    public record InspectionDecisionRequest(
            @NotNull UUID operatorEntityId,
            @NotBlank String reason
    ) {}

    public record TransferInitiateRequest(
            @NotNull UUID assetId,
            @NotBlank String successorName,
            String successorIdentifier,
            @NotBlank String reason,
            @NotNull UUID initiatedBy,
            /** Successor registrar's on-chain address; EVM deployments are verified against it (T3-07). */
            @jakarta.validation.constraints.Pattern(regexp = "^0x[0-9a-fA-F]{40}$") String successorOnchainAddress
    ) {}

    /**
     * One deployment's on-chain handover. {@code deploymentId} may be omitted for single-deployment
     * assets; {@code txHash} and {@code deploymentId} both for an asset without deployments.
     * {@code attested} is the explicit operator attestation required for chains that cannot be verified yet.
     */
    public record OnchainHandoverRequest(
            UUID deploymentId,
            String txHash,
            Boolean attested
    ) {}

    public record TransferCancelRequest(
            @NotBlank String reason
    ) {}
}
