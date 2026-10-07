package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.HandoverBlocker;
import de.makibytes.registerwerk.repo.api.RepoTradeRepository;
import de.makibytes.registerwerk.repo.api.RepoTypes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** A register cannot be handed over while an open repo pledges the asset as collateral (9A-07). */
@Component
class RepoHandoverBlocker implements HandoverBlocker {
    private final RepoTradeRepository trades;

    RepoHandoverBlocker(RepoTradeRepository trades) { this.trades = trades; }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> blocksHandover(UUID assetId) {
        return trades.existsByCollateralAssetIdAndStatusIn(assetId, RepoTypes.TradeStatus.OPEN_STATES)
                ? Optional.of("it is pledged as collateral in an open repo trade - close or settle the repo first")
                : Optional.empty();
    }
}
