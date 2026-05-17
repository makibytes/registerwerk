package de.makibytes.registerwerk.wallet.web.dto;

import java.util.UUID;

public record WalletDefaultResponse(
        UUID chainConfigId,
        String chainIdentifier,
        String chainDisplayName,
        UUID walletId,
        String walletName,
        String walletAddress
) {}
