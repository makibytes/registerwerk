package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.OrderType;
import de.makibytes.registerwerk.trading.api.PaymentOption;

import java.math.BigDecimal;
import java.util.UUID;

record ExecuteOrderRequest(
        UUID listingId,
        BigDecimal quantity,
        OrderType orderType,
        BigDecimal limitPrice,
        PaymentOption paymentOption,
        String walletAddress) {
}
