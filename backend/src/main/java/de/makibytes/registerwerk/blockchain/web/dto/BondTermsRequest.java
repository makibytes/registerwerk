package de.makibytes.registerwerk.blockchain.web.dto;

import de.makibytes.registerwerk.asset.api.DayCountConvention;
import de.makibytes.registerwerk.asset.api.PaymentFrequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Request body for {@code POST /api/v1/assets/{assetId}/bond-terms}.
 *
 * @param faceValue         Nominal value per unit.
 * @param currencyIso       ISO-4217 currency code (e.g. "EUR").
 * @param issueDate         Issue date.
 * @param maturityDate      Maturity date.
 * @param couponRate        Fixed annual coupon rate (null for floating and zero-coupon bonds).
 * @param referenceRate     Reference rate code for floating bonds (e.g. "EURIBOR_3M").
 * @param spread            Spread over reference rate for floating bonds.
 * @param dayCount          Day-count convention.
 * @param paymentFrequency  Coupon payment frequency.
 * @param callable          Whether the bond is callable.
 * @param callSchedule      Optional list of call dates and call prices.
 */
public record BondTermsRequest(
        @NotNull @Positive
        BigDecimal faceValue,

        @NotBlank @Size(min = 3, max = 3)
        String currencyIso,

        @NotNull
        LocalDate issueDate,

        @NotNull
        LocalDate maturityDate,

        @Positive
        BigDecimal couponRate,

        String referenceRate,

        @Positive
        BigDecimal spread,

        @NotNull
        DayCountConvention dayCount,

        @NotNull
        PaymentFrequency paymentFrequency,

        boolean callable,

        List<Map<String, Object>> callSchedule
) {}
