package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.NotBlank;

/** No {@code kind}/{@code managementUrl}/{@code remoteChainKey} fields — re-detected from
 *  {@code url} on every update; see {@code RpcNodeService#updateNode}. */
public record RpcNodeUpdateRequest(
        @NotBlank String url,
        String label
) {}
