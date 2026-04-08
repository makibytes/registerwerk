package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST .../admin/freeze}.
 *
 * @param address    EVM wallet address to freeze (0x-prefixed, 42 chars)
 * @param reason     Short reason written into the on-chain event (e.g. "OFAC SDN list", "BaFin AML §40")
 * @param legalBasis Full legal reference stored in the audit log (e.g. "BaFin Az. 2025-001")
 */
public record FreezeRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String address,

        @NotBlank
        String reason,

        String legalBasis
) {}
