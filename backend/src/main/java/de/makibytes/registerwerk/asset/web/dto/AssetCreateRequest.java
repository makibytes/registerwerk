package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request payload for creating a new digital asset.
 *
 * <p>Economic terms ({@code currency}, {@code issueSize}, {@code denomination},
 * {@code issueDate}, {@code maturityDate}) are optional here — a DRAFT asset can exist before
 * its terms are finalized — but downstream valuation, statements, and tax reporting have
 * nothing to work from until they're set, either at creation or via {@link AssetUpdateRequest}.
 */
public record AssetCreateRequest(
    UUID issuerId,
    @NotBlank String name,
    String isin,
    @NotNull TokenStandard tokenStandard,
    Chain chain,
    Network network,
    @NotNull OnchainLevel onchainLevel,
    Jurisdiction jurisdiction,
    @Size(min = 3, max = 3) String currency,
    @Positive BigDecimal issueSize,
    @Positive BigDecimal denomination,
    LocalDate issueDate,
    LocalDate maturityDate
) {}
