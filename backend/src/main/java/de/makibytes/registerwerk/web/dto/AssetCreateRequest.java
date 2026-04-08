package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.OnchainLevel;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for creating a new digital asset.
 */
public record AssetCreateRequest(
    @NotNull UUID issuerId,
    @NotBlank String name,
    String isin,
    @NotNull TokenStandard tokenStandard,
    @NotNull OnchainLevel onchainLevel
) {}
