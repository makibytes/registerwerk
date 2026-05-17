package de.makibytes.registerwerk.chain.web.dto;

import java.util.List;
import java.util.UUID;

/** Aggregated health view for one chain: its config + all its RPC nodes. */
public record ChainHealthResponse(
        UUID id,
        String identifier,
        String displayName,
        String chainType,
        String networkType,
        Long chainId,
        boolean enabled,
        List<RpcNodeResponse> nodes
) {}
