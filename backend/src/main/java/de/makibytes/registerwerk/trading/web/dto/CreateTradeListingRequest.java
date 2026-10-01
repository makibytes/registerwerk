package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.api.PaymentOption;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateTradeListingRequest(
        @NotNull UUID holderId,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal pricePerUnit,
        boolean useCompanyDefaultPaymentOption,
        List<PaymentOption> allowedPaymentOptions,
        // Seller pre-authorisation for the DEMO instant path (5A-01); refused unless the demo
        // property is on. Replaces the buyer-owned company setting.
        boolean allowInstantSettlement,
        // Settlement currency (ISO 4217). Optional: defaults to the payment rail's currency
        // (STABLECOIN) or the asset's denomination currency; rejected when neither resolves (5A-02).
        String currency,
        // Payment rail code; mandatory when STABLECOIN is offered, otherwise must be omitted.
        String paymentRailCode,
        // Bilateral listing (5C-06): only this entity may see and buy it. Mandatory under
        // registerwerk.trading.venue-classification=BILATERAL_ONLY.
        UUID targetEntityId) {

    public CreateTradeListingRequest(UUID holderId, BigDecimal quantity, BigDecimal pricePerUnit,
                                     boolean useCompanyDefaultPaymentOption, List<PaymentOption> allowedPaymentOptions,
                                     boolean allowInstantSettlement) {
        this(holderId, quantity, pricePerUnit, useCompanyDefaultPaymentOption, allowedPaymentOptions,
                allowInstantSettlement, null, null, null);
    }

    public CreateTradeListingRequest(UUID holderId, BigDecimal quantity, BigDecimal pricePerUnit,
                                     boolean useCompanyDefaultPaymentOption, List<PaymentOption> allowedPaymentOptions) {
        this(holderId, quantity, pricePerUnit, useCompanyDefaultPaymentOption, allowedPaymentOptions, false);
    }
}
