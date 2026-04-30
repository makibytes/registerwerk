package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.domain.trading.TradingAssetType;

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
