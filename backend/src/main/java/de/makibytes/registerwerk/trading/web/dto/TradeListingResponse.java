package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.asset.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TradeListingResponse(
        UUID id,
        TradingVenueCode venueCode,
        UUID assetId,
        String assetNumber,
        String assetName,
        String isin,
        TradingAssetType assetType,
        TokenStandard tokenStandard,
        Chain chain,
        ListingStatus status,
        BigDecimal quantityTotal,
        BigDecimal quantityAvailable,
        BigDecimal pricePerUnit,
        List<PaymentOption> allowedPaymentOptions,
        Instant createdAt) {
}
