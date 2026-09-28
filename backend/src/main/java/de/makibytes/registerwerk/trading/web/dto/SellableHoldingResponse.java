package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.TradingAssetType;

import java.math.BigDecimal;
import java.util.UUID;

public record SellableHoldingResponse(
        UUID holderId,
        UUID assetId,
        String assetNumber,
        String assetName,
        String isin,
        TradingAssetType assetType,
        TokenStandard tokenStandard,
        Chain chain,
        BigDecimal ownedQuantity,
        BigDecimal availableQuantity,
        String walletAddress,
        Jurisdiction jurisdiction,
        // T3-09: false when the asset has a confirmed chain deployment and off-chain settlement
        // on deployed assets is disabled (the default) — a listing would be refused with 409.
        boolean listable,
        // T3-09: true when listing is allowed only because the simulated off-chain settlement flag
        // is on for a chain-deployed asset (demo) — the trading desk shows a notice.
        boolean simulatedOffchainSettlement) {
}
