package de.makibytes.registerwerk.lending.web.dto;

import de.makibytes.registerwerk.lending.api.LendingPositionStatus;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public record LendingPositionResponse(
        UUID marketId,
        String walletAddress,
        BigInteger collateralAmount,
        BigInteger currentDebt,
        BigInteger healthFactorWad,
        // False means the on-chain healthFactor() itself flagged its collateral mark as unpriced
        // or stale — healthFactorWad must not be treated as trustworthy in
        // that case. Null when healthFactorWad itself is null (no debt, or the read failed).
        Boolean healthFactorReliable,
        LendingPositionStatus status,
        Instant lastSyncedAt
) {}
