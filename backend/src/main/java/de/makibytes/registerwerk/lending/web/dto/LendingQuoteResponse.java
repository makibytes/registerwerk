package de.makibytes.registerwerk.lending.web.dto;

import java.math.BigInteger;
import java.util.UUID;

/** {@code priceUpdatedAt} is the raw on-chain unix-seconds timestamp from {@code IRepoOracle.price}. */
public record LendingQuoteResponse(
        UUID marketId,
        BigInteger collateralAmount,
        BigInteger pricePerUnit,
        BigInteger priceUpdatedAt,
        BigInteger maxBorrowAmount,
        Integer lltvBps,
        BigInteger utilizationWad,
        BigInteger borrowRateWad
) {}
