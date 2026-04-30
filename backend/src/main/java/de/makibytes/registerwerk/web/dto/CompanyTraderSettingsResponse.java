package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.trading.PaymentOption;

import java.util.List;

public record CompanyTraderSettingsResponse(
        PaymentOption defaultPaymentOption,
        boolean immediateSettlementEnabled,
        List<CompanyTraderWalletDefaultResponse> walletDefaults) {
}
