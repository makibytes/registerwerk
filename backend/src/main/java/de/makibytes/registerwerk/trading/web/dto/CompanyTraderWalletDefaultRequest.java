package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.WalletTargetType;

import java.util.UUID;

public record CompanyTraderWalletDefaultRequest(
        TradingAssetType assetType,
        WalletTargetType targetType,
        UUID endpointId,
        String walletAddress) {
}
