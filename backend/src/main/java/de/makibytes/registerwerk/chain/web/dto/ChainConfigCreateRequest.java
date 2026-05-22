package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for creating a new chain configuration entry.
 */
public record ChainConfigCreateRequest(
        @NotBlank String identifier,
        @NotBlank String displayName,
        @NotNull  String chainType,        // "EVM" | "SOLANA"
        @NotNull  String networkType,      // "MAINNET" | "TESTNET"
        Long chainId,
        @NotBlank String rpcUrl,
        String wsUrl,
        String blockExplorerUrl,
        String graphNodeUrl,
        String graphSubgraphName
) {}
