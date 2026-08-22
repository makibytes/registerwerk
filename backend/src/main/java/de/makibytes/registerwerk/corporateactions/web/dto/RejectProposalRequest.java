package de.makibytes.registerwerk.corporateactions.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Operator-supplied reason for rejecting an issuer's PROPOSED corporate action. */
public record RejectProposalRequest(@NotBlank String reason) {}
