package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.internal.HolderChangeRequest;
import de.makibytes.registerwerk.asset.internal.InstructingParty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * An issuer's request for a register-entry change (T3-13). {@code payload} carries the fields of
 * {@link HolderCreateRequest} / {@link SingleEntryHolderCreateRequest} (ADD_HOLDER, with
 * {@code singleEntry=true} for a single entry) or of {@link SingleEntryAttributesUpdateRequest}
 * (UPDATE_ATTRIBUTES, together with {@code holderId}).
 */
public record HolderChangeRequestBody(
    @NotNull HolderChangeRequest.RequestType requestType,
    UUID holderId,
    Map<String, Object> payload,
    @NotNull InstructingParty instructingParty,
    @NotBlank String instructionReference
) {}
