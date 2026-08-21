package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new chain configuration entry.
 */
public record ChainConfigCreateRequest(
        @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") String identifier,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull @Pattern(regexp = "(?i)EVM|SOLANA|STARKNET|STELLAR|CANTON") String chainType,
        @NotNull @Pattern(regexp = "(?i)MAINNET|TESTNET") String networkType,
        @Positive Long chainId,
        @NotBlank @Size(max = 512) @Pattern(regexp = "https?://\\S+") String rpcUrl,
        @Size(max = 512) @Pattern(regexp = "wss?://\\S+") String wsUrl,
        @Size(max = 512) @Pattern(regexp = "https?://\\S+") String blockExplorerUrl,
        @Size(max = 512) @Pattern(regexp = "https?://\\S+") String graphNodeUrl,
        @Size(max = 200) String graphSubgraphName,
        /** Optional; defaults to {@code DEPTH_BASED} when omitted (existing behavior). */
        @Pattern(regexp = "(?i)TAG_BASED|DEPTH_BASED|INSTANT") String finalityModel,
        /** Optional; null means unknown — the gate shows no ETA rather than guessing one. */
        @Positive Integer avgBlockSeconds,
        /** Optional; defaults to {@code RPC_SELF_PROBE} when omitted (existing behavior) — opt a
         *  chain into {@code CHAINCACHE} only once it has an enabled chaincache-kind RpcNode. */
        @Pattern(regexp = "(?i)RPC_SELF_PROBE|CHAINCACHE") String finalitySource
) {}
