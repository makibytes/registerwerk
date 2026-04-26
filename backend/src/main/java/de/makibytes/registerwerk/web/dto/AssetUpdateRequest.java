package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.Jurisdiction;

import java.util.Map;

/**
 * Request payload for partial update of an asset. All fields are optional.
 */
public record AssetUpdateRequest(
    String name,
    String isin,
    Map<String, Object> publicData,
    Jurisdiction jurisdiction
) {}
