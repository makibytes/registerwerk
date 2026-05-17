package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.trading.internal.PaymentOption;

import java.util.List;

public record CompanyTraderSettingsResponse(
        PaymentOption defaultPaymentOption,
        boolean immediateSettlementEnabled,
        List<CompanyTraderWalletDefaultResponse> walletDefaults) {
}
