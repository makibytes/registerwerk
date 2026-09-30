package de.makibytes.registerwerk.blockchain.api;

/**
 * The signing chain id could not be established from the pinned {@code chain_config.chain_id}:
 * either it is unset, or the RPC node answers a different {@code eth_chainId}. The registry never
 * signs with a chain id learned from a node.
 */
public class ChainIdMismatchException extends IllegalStateException {

    public ChainIdMismatchException(String message) {
        super(message);
    }

    public static ChainIdMismatchException unpinned(String identifier) {
        return new ChainIdMismatchException("Chain '" + identifier
                + "' has no pinned chain_id; refusing to sign (set chain_config.chain_id)");
    }

    public static ChainIdMismatchException mismatch(String identifier, long pinned, long reported) {
        return new ChainIdMismatchException("RPC node for chain '" + identifier + "' reports chain id "
                + reported + " but the pinned chain id is " + pinned + "; refusing to sign");
    }
}
