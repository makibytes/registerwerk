package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.WalletTargetType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CompanyTraderWalletDefaultRequest(
        TradingAssetType assetType,
        @NotNull WalletTargetType targetType,
        UUID endpointId,
        @Size(max = 128) String walletAddress) {

    @AssertTrue(message = "endpointId and walletAddress must match targetType")
    public boolean isTargetValid() {
        if (targetType == null) {
            return true;
        }
        return targetType == WalletTargetType.ENDPOINT
                ? endpointId != null && (walletAddress == null || walletAddress.isBlank())
                : endpointId == null && walletAddress != null && !walletAddress.isBlank();
    }
}
