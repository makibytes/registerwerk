package de.makibytes.registerwerk.chain.api;

import org.springframework.stereotype.Component;

/**
 * Builds block-explorer URLs for transactions on any supported chain.
 * Handles a null or blank {@code blockExplorerUrl} gracefully by returning the
 * raw transaction hash when no base URL is available.
 */
@Component
public class ExplorerUrlBuilder {

    /**
     * Returns the explorer URL for a given transaction hash.
     * <ul>
     *   <li>EVM: {@code <baseUrl>/tx/<txHash>}</li>
     *   <li>Solana mainnet: {@code <baseUrl>/tx/<txHash>}</li>
     *   <li>Solana testnet/devnet: {@code <baseUrl>/tx/<txHash>?cluster=devnet}</li>
     * </ul>
     */
    public String buildTxUrl(ChainConfig chain, String txHash) {
        String base = resolveBase(chain);
        if (base == null) {
            return txHash;
        }

        String url = base + "/tx/" + txHash;

        if (chain.getChainType() == ChainConfig.ChainType.SOLANA
                && chain.getNetworkType() != ChainConfig.NetworkType.MAINNET) {
            url = url + "?cluster=devnet";
        }

        return url;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveBase(ChainConfig chain) {
        if (chain == null || chain.getBlockExplorerUrl() == null
                || chain.getBlockExplorerUrl().isBlank()) {
            return null;
        }
        // Strip any trailing slash to ensure consistent URL construction.
        return chain.getBlockExplorerUrl().stripTrailing().replaceAll("/+$", "");
    }
}
