package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.trading.OrderType;
import de.makibytes.registerwerk.domain.trading.PaymentOption;
import de.makibytes.registerwerk.domain.trading.WalletPreferenceMode;
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
