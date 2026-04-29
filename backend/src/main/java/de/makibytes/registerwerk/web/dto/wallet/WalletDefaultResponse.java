package de.makibytes.registerwerk.web.dto.wallet;

import java.util.UUID;

public record WalletDefaultResponse(
        UUID chainConfigId,
        String chainIdentifier,
        String chainDisplayName,
        UUID walletId,
        String walletName,
        String walletAddress
) {}
