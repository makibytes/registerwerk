package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.asset.events.HolderRemovedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 5A-04 / 5A-05: keeps trading consistent with the register and the asset lifecycle.
 * <ul>
 *   <li>{@link HolderRemovedEvent} (removal or register handover): the seller's row keeps its
 *       nominal as retained evidence, so trading must stop offering and settling against it;</li>
 *   <li>{@link AssetSuspendedEvent} / {@link AssetRedeemedEvent}: the asset module cannot refuse on
 *       trading's behalf (module cycle), so this listener is the mechanism. Reactivation does not
 *       resurrect listings - the seller relists.</li>
 * </ul>
 * The settlement path additionally re-checks the row and asset status itself, so the small window
 * before this after-commit listener runs is not exploitable.
 */
@Component
class HolderRemovedTradingListener {

    private final TradeInvalidationService invalidation;

    HolderRemovedTradingListener(TradeInvalidationService invalidation) {
        this.invalidation = invalidation;
    }

    @ApplicationModuleListener
    void onHolderRemoved(HolderRemovedEvent event) {
        invalidation.onHolderRemoved(event.holderId());
    }

    @ApplicationModuleListener
    void onAssetSuspended(AssetSuspendedEvent event) {
        invalidation.onAssetUnavailable(event.assetId(), "ASSET_SUSPENDED", "The asset was suspended");
    }

    @ApplicationModuleListener
    void onAssetRedeemed(AssetRedeemedEvent event) {
        invalidation.onAssetUnavailable(event.assetId(), "ASSET_REDEEMED", "The asset was redeemed");
    }
}
