package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        @NotNull @Positive @Digits(integer = 20, fraction = 18)
        BigDecimal faceValue,

        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter ISO-4217 code")
        String currencyIso,

        @NotNull
        LocalDate issueDate,

        @NotNull
        LocalDate maturityDate,

        @DecimalMin("0") @Digits(integer = 2, fraction = 8)
        BigDecimal couponRate,

        @Size(max = 32)
        String referenceRate,

        @Digits(integer = 2, fraction = 8)
        BigDecimal spread,

        @NotNull
        DayCountConvention dayCount,

        @NotNull
        PaymentFrequency paymentFrequency,

        boolean callable,

        @Valid @Size(max = 100)
        List<CallScheduleEntry> callSchedule
) {
    public record CallScheduleEntry(
            @NotNull LocalDate callDate,
            @NotNull @Positive @Digits(integer = 10, fraction = 8) BigDecimal callPrice) {}

    @AssertTrue(message = "maturityDate must be after issueDate")
    public boolean isDateRangeValid() {
        return issueDate == null || maturityDate == null || maturityDate.isAfter(issueDate);
    }

    @AssertTrue(message = "callSchedule is only allowed for callable bonds and dates must be between issue and maturity")
    public boolean isCallScheduleValid() {
        if (callSchedule == null || callSchedule.isEmpty()) {
            return true;
        }
        if (!callable || issueDate == null || maturityDate == null) {
            return false;
        }
        return callSchedule.stream().allMatch(entry -> entry != null && entry.callDate() != null
                && entry.callDate().isAfter(issueDate) && entry.callDate().isBefore(maturityDate));
    }
}
