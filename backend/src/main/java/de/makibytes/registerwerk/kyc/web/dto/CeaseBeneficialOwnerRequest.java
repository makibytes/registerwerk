package de.makibytes.registerwerk.kyc.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Ceasing a beneficial owner: mandatory reason, optional id of the register extract that evidences it. */
public record CeaseBeneficialOwnerRequest(
        @NotBlank @Size(max = 2000) String reason,
        UUID documentId
) {}
