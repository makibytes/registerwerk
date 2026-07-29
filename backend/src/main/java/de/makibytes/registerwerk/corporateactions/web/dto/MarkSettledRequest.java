package de.makibytes.registerwerk.corporateactions.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Operator-supplied settlement reference (manual on-chain tx hash, bank transfer reference,
 *  etc.) for {@code CorporateActionAdminController.markSettled} — the manual path used when no
 *  automated on-chain settlement adapter exists for the asset's token standard. */
public record MarkSettledRequest(@NotBlank String reference) {}
