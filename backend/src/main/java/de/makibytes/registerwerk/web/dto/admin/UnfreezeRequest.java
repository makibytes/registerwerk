package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST .../admin/unfreeze}.
 *
 * @param address EVM wallet address to unfreeze
 */
public record UnfreezeRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String address
) {}
