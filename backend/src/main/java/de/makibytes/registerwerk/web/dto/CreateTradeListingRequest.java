package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.trading.PaymentOption;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateTradeListingRequest(
        @NotNull UUID holderId,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal pricePerUnit,
        boolean useCompanyDefaultPaymentOption,
        List<PaymentOption> allowedPaymentOptions) {
}
