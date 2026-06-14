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
            @NotNull UUID initiatedBy
    ) {}

    public record OnchainHandoverRequest(
            @NotBlank String txHash
    ) {}

    public record TransferCancelRequest(
            @NotBlank String reason
    ) {}
}
