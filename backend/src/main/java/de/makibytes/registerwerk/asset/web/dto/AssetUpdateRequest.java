package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.chain.api.Network;

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
    Network network
) {}
