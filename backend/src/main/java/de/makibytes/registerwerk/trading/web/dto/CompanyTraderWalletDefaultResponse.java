package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.internal.TradingAssetType;
import de.makibytes.registerwerk.trading.internal.WalletTargetType;

import java.util.UUID;

public record CompanyTraderWalletDefaultResponse(
        UUID id,
        TradingAssetType assetType,
        WalletTargetType targetType,
        UUID endpointId,
        String walletAddress) {
}
