package de.makibytes.registerwerk.blockchain.web.dto;

import java.util.Map;

/** Maps each requested address to its resolved name, or omits it when unresolvable. */
public record AddressResolveResponse(
        Map<String, String> resolutions
) {}
