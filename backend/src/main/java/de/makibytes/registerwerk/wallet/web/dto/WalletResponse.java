package de.makibytes.registerwerk.wallet.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        String name,
        String type,
        String address,
        List<UUID> defaultForChains,
        Instant createdAt,
        Instant updatedAt
) {}
