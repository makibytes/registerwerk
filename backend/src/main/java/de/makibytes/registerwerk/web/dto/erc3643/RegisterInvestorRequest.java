package de.makibytes.registerwerk.web.dto.erc3643;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for registering an investor in an ERC-3643 IdentityRegistry.
 */
public record RegisterInvestorRequest(
    /** Investor's on-chain wallet address (0x-prefixed). */
    @NotBlank String walletAddress,
    /** UUID of the investor's legal entity in this registry. */
    @NotNull UUID legalEntityId,
    /** UUID of the chain_config row (identifies which chain the ONCHAINID is on). */
    @NotNull UUID chainConfigId,
    /** ISO-3166-1 numeric country code (optional; used for country-restriction compliance). */
    Short countryCode
) {}
