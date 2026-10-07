package de.makibytes.registerwerk.chain.web.dto;

import jakarta.validation.constraints.AssertTrue;
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
        @Positive Integer avgBlockSeconds
        // No finalitySource field: it is fully auto-derived from the chain's node set — see
        // ChainConfig.FinalitySource's javadoc. There is nothing for an operator to set here.
) {
    /**
     * An EVM chain row without {@code chainId} would sign with whatever the node answers to
     * {@code eth_chainId} (or refuse every submission, see {@code UnpinnedEvmChainPreflight}): the id pins the
     * network the signer is allowed to sign for, so it is mandatory for EVM chains. Other chain families have no
     * EIP-155 id and may omit it.
     */
    @AssertTrue(message = "chainId is required for EVM chains (it pins the network the signer may sign for)")
    public boolean isChainIdProvidedForEvm() {
        return chainType == null || !"EVM".equalsIgnoreCase(chainType) || chainId != null;
    }
}
