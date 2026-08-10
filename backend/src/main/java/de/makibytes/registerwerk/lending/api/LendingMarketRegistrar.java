package de.makibytes.registerwerk.lending.api;

import java.util.UUID;

/** Narrow cross-module command used by controlled bootstrap/import processes. */
public interface LendingMarketRegistrar {
    void registerVerifiedMarket(UUID chainConfigId, String marketAddress, String vaultAddress,
                                UUID collateralAssetId, String loanRailCode,
                                UUID registeredBy, String registeredByRole);
}

