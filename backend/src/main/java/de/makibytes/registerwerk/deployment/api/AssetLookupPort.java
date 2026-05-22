package de.makibytes.registerwerk.deployment.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for cross-module services to look up Asset business data without importing
 * the Asset entity directly (breaks module cycles).
 * Implemented by asset/internal/AssetLookupPortImpl.
 */
public interface AssetLookupPort {

    record AssetInfo(
            UUID id,
            String name,
            String isin,
            TokenStandard tokenStandard,
            String chain,
            String network,
            UUID issuerId,
            String assetNumber,
            String status
    ) {}

    Optional<AssetInfo> findById(UUID assetId);

    List<AssetInfo> findAll();

    List<AssetInfo> findByIssuerId(UUID issuerId);
}
