package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No {@code kind}/{@code managementUrl}/{@code remoteChainKey} fields — re-detected from
 *  {@code url} on every update; see {@code RpcNodeService#updateNode}. */
public record RpcNodeUpdateRequest(
        @NotBlank @Size(max = 512) String url,
        String label
) {}
