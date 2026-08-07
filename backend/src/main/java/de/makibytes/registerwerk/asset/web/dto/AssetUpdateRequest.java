package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.chain.api.Network;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Request payload for partial update of an asset. All fields are optional.
 */
public record AssetUpdateRequest(
    String name,
    String isin,
    Map<String, Object> publicData,
    Jurisdiction jurisdiction,
    Chain chain,
    Network network,
    @Size(min = 3, max = 3) String currency,
    @Positive BigDecimal issueSize,
    @Positive BigDecimal denomination,
    LocalDate issueDate,
    LocalDate maturityDate
) {}
