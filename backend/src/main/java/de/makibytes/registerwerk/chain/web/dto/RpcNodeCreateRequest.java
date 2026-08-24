package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.NotBlank;

/** No {@code kind}/{@code managementUrl}/{@code remoteChainKey} fields — whether this node is a
 *  chaincache connection is auto-detected from {@code url} alone; see
 *  {@code RpcNodeService#addNode} / {@code ChaincacheClient#detect}. */
public record RpcNodeCreateRequest(
        @NotBlank String url,
        String label
) {}
