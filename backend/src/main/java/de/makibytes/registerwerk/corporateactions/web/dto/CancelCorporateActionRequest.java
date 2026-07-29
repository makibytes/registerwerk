package de.makibytes.registerwerk.corporateactions.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Operator-supplied reason for {@code CorporateActionAdminController.cancel}. */
public record CancelCorporateActionRequest(@NotBlank String reason) {}
