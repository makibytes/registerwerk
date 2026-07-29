package de.makibytes.registerwerk.lending.web.dto;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public record LendingSupplyPositionResponse(
        UUID marketId,
        String walletAddress,
        BigInteger currentClaim,
        Instant lastSyncedAt
) {}
