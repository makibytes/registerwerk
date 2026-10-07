package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.asset.api.HandoverBlocker;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingPositionRepository;
import de.makibytes.registerwerk.lending.api.LendingPositionStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** A register cannot be handed over while the asset is locked as collateral by an open lending position (9A-07). */
@Component
class LendingHandoverBlocker implements HandoverBlocker {
    private final LendingMarketRepository markets;
    private final LendingPositionRepository positions;

    LendingHandoverBlocker(LendingMarketRepository markets, LendingPositionRepository positions) {
        this.markets = markets;
        this.positions = positions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> blocksHandover(UUID assetId) {
        for (LendingMarket market : markets.findByCollateralAssetId(assetId)) {
            if (market.getStatus() != LendingMarketStatus.RETIRED
                    && positions.existsByMarketIdAndStatus(market.getId(), LendingPositionStatus.OPEN)) {
                return Optional.of("it is locked as collateral by an open lending position on market "
                        + market.getId() + " - repay and withdraw first");
            }
        }
        return Optional.empty();
    }
}
