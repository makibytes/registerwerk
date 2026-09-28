package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.internal.InstructingParty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload to update a single-entry holder's §17(2) eWpG attributes on
 * the instruction of an authorised party (§18(1)), executed by the operator with 4-eyes (T3-13).
 * All value fields are optional; a blank text means "no change" — removing a right or restriction
 * needs the explicit {@code clear*} flag.
 */
public record SingleEntryAttributesUpdateRequest(
    Boolean isConsumer,
    @Size(max = 2000) String thirdPartyRights,
    @Size(max = 2000) String disposalRestrictions,
    @Size(max = 2000) String legalCapacityNote,
    boolean clearThirdPartyRights,
    boolean clearDisposalRestrictions,
    @NotNull InstructingParty instructingParty,
    @NotBlank String instructionReference
) {}
