package de.makibytes.registerwerk.corporateactions.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * An operator's audited override of the issuer-attestation requirement, for an issuer who never
 * logs in to attest — see {@code CorporateActionService.overrideIssuerAttestation}. {@code reason}
 * is mandatory and stored verbatim (prefixed) into {@code issuerAttestationRef}, and published as
 * a distinct event type from a genuine issuer attestation, so this exception path is always
 * visible and countable, never silently indistinguishable from the real thing.
 */
public record OverrideAttestationRequest(@NotBlank String reason) {}
