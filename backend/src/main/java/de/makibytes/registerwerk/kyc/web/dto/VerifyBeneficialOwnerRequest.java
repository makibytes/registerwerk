package de.makibytes.registerwerk.kyc.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Verification of a beneficial owner against a stored KYC document (register extract, identity document). */
public record VerifyBeneficialOwnerRequest(@NotNull UUID documentId) {}
