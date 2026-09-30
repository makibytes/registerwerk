package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No {@code kind}/{@code managementUrl}/{@code remoteChainKey} fields — whether this node is a
 *  chaincache connection is auto-detected from {@code url} alone; see
 *  {@code RpcNodeService#addNode} / {@code ChaincacheClient#detect}. */
public record RpcNodeCreateRequest(
        @NotBlank @Size(max = 512) String url,
        String label
) {}
