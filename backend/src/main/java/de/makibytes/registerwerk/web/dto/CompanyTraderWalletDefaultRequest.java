package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.trading.TradingAssetType;
import de.makibytes.registerwerk.domain.trading.WalletTargetType;

import java.util.UUID;

public record CompanyTraderWalletDefaultRequest(
        TradingAssetType assetType,
        WalletTargetType targetType,
        UUID endpointId,
        String walletAddress) {
}
