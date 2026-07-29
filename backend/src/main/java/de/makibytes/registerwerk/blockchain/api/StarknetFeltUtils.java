package de.makibytes.registerwerk.blockchain.api;

import java.math.BigInteger;

/**
 * Shared Starknet felt (field element) encoding utilities — used by both the Cairo deployment
 * path ({@code blockchain.internal.deploy.StarknetTokenService}) and the transfer indexer
 * ({@code indexer.internal.StarknetTransferSyncService}), which live in different Modulith
 * modules and therefore need this in a shared {@code api} package rather than duplicated.
 */
public final class StarknetFeltUtils {

    private StarknetFeltUtils() {}

    /** Parses a {@code "0x"}-prefixed (or bare) hex string into its felt value. */
    public static BigInteger parseHexFelt(String hex) {
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        return new BigInteger(h, 16);
    }
}
