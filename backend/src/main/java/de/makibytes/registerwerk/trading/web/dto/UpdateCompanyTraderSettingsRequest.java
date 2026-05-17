package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.internal.PaymentOption;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateCompanyTraderSettingsRequest(
        @NotNull PaymentOption defaultPaymentOption,
        boolean immediateSettlementEnabled,
        List<CompanyTraderWalletDefaultRequest> walletDefaults) {
}
