package de.makibytes.registerwerk.blockchain.api;

import java.util.UUID;

/**
 * Shared Stellar asset-identifier utilities — used by both the asset issuance path
 * ({@code blockchain.internal.deploy.StellarAssetService}) and the transfer indexer
 * ({@code indexer.internal.StellarTransferSyncService}), which live in different Modulith
 * modules and therefore need this in a shared {@code api} package rather than duplicated.
 */
public final class StellarUtils {

    private StellarUtils() {}

    /**
     * Derives a Stellar asset code (max 12 chars) deterministically from a Registerwerk asset
     * UUID: first 12 hex characters with dashes stripped, upper-cased.
     */
    public static String deriveAssetCode(UUID assetId) {
        return assetId.toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
