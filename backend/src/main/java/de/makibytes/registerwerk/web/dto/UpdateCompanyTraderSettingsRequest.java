package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.trading.PaymentOption;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateCompanyTraderSettingsRequest(
        @NotNull PaymentOption defaultPaymentOption,
        boolean immediateSettlementEnabled,
        List<CompanyTraderWalletDefaultRequest> walletDefaults) {
}
