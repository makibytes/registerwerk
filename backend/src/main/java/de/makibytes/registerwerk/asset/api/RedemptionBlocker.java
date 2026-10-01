package de.makibytes.registerwerk.asset.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Lets a module that commits units of an asset (the repo desk) veto the redemption of that asset
 * (Phase 5, 5A-08 / T5-10). Implemented outside {@code asset} to avoid an asset -> repo dependency;
 * collected by {@code AssetLifecycleService.redeem}. Coupon record-date snapshots are not blocked.
 */
public interface RedemptionBlocker {

    /** A human-readable reason when redemption of {@code assetId} must be refused, else empty. */
    Optional<String> blocksRedemption(UUID assetId);
}
