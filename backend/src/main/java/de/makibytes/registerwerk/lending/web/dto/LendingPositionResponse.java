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
        // Loan-token cash a liquidation credited to the wallet, claimable via
        // claimLiquidationSurplus() on the market; zero when there is none.
        BigInteger liquidationSurplus,
        LendingPositionStatus status,
        Instant lastSyncedAt,
        // True when the last refresh could not read the chain: values are the previous ones and
        // "balance may be out of date" must be shown. lastSyncError is an operator diagnostic.
        boolean stale,
        String lastSyncError,
        // True when the market's collateral balance is below its recorded total (forced move not yet
        // reconciled): collateralAmount may overstate what the market can deliver.
        boolean collateralUnverified
) {}
