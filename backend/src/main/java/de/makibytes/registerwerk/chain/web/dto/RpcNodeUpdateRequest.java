package de.makibytes.registerwerk.chain.web.dto;

import de.makibytes.registerwerk.chain.api.RpcNode;
import jakarta.validation.constraints.NotBlank;

public record RpcNodeUpdateRequest(
        @NotBlank String url,
        String label,
        RpcNode.NodeKind kind,
        String managementUrl,
        String remoteChainKey
) {}
