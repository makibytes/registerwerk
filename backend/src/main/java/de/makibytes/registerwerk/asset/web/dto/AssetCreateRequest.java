package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.asset.api.TokenStandard;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for creating a new digital asset.
 */
public record AssetCreateRequest(
    UUID issuerId,
    @NotBlank String name,
    String isin,
    @NotNull TokenStandard tokenStandard,
    Chain chain,
    Network network,
    @NotNull OnchainLevel onchainLevel,
    Jurisdiction jurisdiction
) {}
