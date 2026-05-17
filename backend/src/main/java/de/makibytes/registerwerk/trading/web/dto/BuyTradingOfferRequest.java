package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.internal.OrderType;
import de.makibytes.registerwerk.trading.internal.PaymentOption;
import de.makibytes.registerwerk.trading.internal.WalletPreferenceMode;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record BuyTradingOfferRequest(
        @NotNull BigDecimal quantity,
        @NotNull OrderType orderType,
        BigDecimal limitPrice,
        PaymentOption paymentOption,
        WalletPreferenceMode walletPreferenceMode,
        UUID endpointId,
        String walletAddress) {
}
