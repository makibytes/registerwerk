package de.makibytes.registerwerk.web.dto.wallet;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletBalanceResponse(
        UUID chainConfigId,
        String chainIdentifier,
        String chainDisplayName,
        String nativeCurrencySymbol,
        BigDecimal balance,
        String error
) {}
