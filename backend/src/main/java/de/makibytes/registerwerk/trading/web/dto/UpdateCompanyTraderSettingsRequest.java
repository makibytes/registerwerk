package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.api.PaymentOption;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateCompanyTraderSettingsRequest(
        @NotNull PaymentOption defaultPaymentOption,
        boolean immediateSettlementEnabled,
        @Valid @Size(max = 50) List<CompanyTraderWalletDefaultRequest> walletDefaults) {
}
