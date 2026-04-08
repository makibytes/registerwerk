package de.makibytes.registerwerk.web.dto;

import java.util.Map;

/**
 * Request payload for partial update of an asset. All fields are optional.
 */
public record AssetUpdateRequest(
    String name,
    String isin,
    Map<String, Object> publicData
) {}
