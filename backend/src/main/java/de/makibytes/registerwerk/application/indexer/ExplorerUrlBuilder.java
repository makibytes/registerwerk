package de.makibytes.registerwerk.application.indexer;

import de.makibytes.registerwerk.domain.chain.ChainConfig;
import org.springframework.stereotype.Component;

/**
 * Builds block-explorer URLs for transactions, addresses, and blocks on any supported chain.
 * All methods handle a null or blank {@code blockExplorerUrl} gracefully by returning the
 * raw identifier (hash / address / block number) when no base URL is available.
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

    /**
     * Returns the explorer URL for a given address.
     * <ul>
     *   <li>EVM: {@code <baseUrl>/address/<address>}</li>
     *   <li>Solana: {@code <baseUrl>/address/<address>} (with cluster query param for devnet)</li>
     * </ul>
     */
    public String buildAddressUrl(ChainConfig chain, String address) {
        String base = resolveBase(chain);
        if (base == null) {
            return address;
        }

        String url = base + "/address/" + address;

        if (chain.getChainType() == ChainConfig.ChainType.SOLANA
                && chain.getNetworkType() != ChainConfig.NetworkType.MAINNET) {
            url = url + "?cluster=devnet";
        }

        return url;
    }

    /**
     * Returns the explorer URL for a given block number (EVM) or slot (Solana).
     */
    public String buildBlockUrl(ChainConfig chain, long blockNumber) {
        String base = resolveBase(chain);
        if (base == null) {
            return String.valueOf(blockNumber);
        }

        String segment = chain.getChainType() == ChainConfig.ChainType.SOLANA ? "block" : "block";
        String url = base + "/" + segment + "/" + blockNumber;

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
