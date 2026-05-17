package de.makibytes.registerwerk.indexer;

import java.util.UUID;

/** Public API for indexer operations: holder sync and token history queries. */
public interface IndexerApi {

    void syncHoldersFromBlockchain(UUID assetId);

    void manualRefreshIssuance(String assetId);
}
