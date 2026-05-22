package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
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
        String walletAddress) {
}
