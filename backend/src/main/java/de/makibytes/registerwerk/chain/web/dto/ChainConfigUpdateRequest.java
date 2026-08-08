package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** PATCH contract: every field is optional, but supplied values must remain usable. */
public record ChainConfigUpdateRequest(
        @Size(min = 1, max = 120) @Pattern(regexp = ".*\\S.*") String displayName,
        @Positive Long chainId,
        @Size(min = 1, max = 512) @Pattern(regexp = "https?://\\S+") String rpcUrl,
        @Size(max = 512) @Pattern(regexp = "wss?://\\S+") String wsUrl,
        @Size(max = 512) @Pattern(regexp = "https?://\\S+") String blockExplorerUrl,
        @Size(max = 512) @Pattern(regexp = "https?://\\S+") String graphNodeUrl,
        @Size(max = 200) String graphSubgraphName
) {}
