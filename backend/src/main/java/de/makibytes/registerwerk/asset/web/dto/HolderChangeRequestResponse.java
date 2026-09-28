package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.internal.HolderChangeRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record HolderChangeRequestResponse(
    UUID id,
    UUID assetId,
    String requestType,
    UUID holderId,
    Map<String, Object> payload,
    String instructingParty,
    String instructionReference,
    String status,
    UUID requestedBy,
    Instant requestedAt,
    UUID decidedBy,
    Instant decidedAt,
    String decisionReason
) {
    public static HolderChangeRequestResponse from(HolderChangeRequest r) {
        return new HolderChangeRequestResponse(r.getId(), r.getAssetId(), r.getRequestType().name(), r.getHolderId(),
                r.getPayload(), r.getInstructingParty().name(), r.getInstructionReference(), r.getStatus().name(),
                r.getRequestedBy(), r.getRequestedAt(), r.getDecidedBy(), r.getDecidedAt(), r.getDecisionReason());
    }
}
