package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.RedemptionBlocker;
import de.makibytes.registerwerk.repo.api.RepoTradeRepository;
import de.makibytes.registerwerk.repo.api.RepoTypes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** An asset cannot be redeemed while an open repo uses it as collateral (5A-08 / T5-10). */
@Component
class RepoRedemptionBlocker implements RedemptionBlocker {
    private final RepoTradeRepository trades;

    RepoRedemptionBlocker(RepoTradeRepository trades) { this.trades = trades; }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> blocksRedemption(UUID assetId) {
        return trades.existsByCollateralAssetIdAndStatusIn(assetId, RepoTypes.TradeStatus.OPEN_STATES)
                ? Optional.of("it is pledged as collateral in an open repo trade - close or settle the repo first")
                : Optional.empty();
    }
}
