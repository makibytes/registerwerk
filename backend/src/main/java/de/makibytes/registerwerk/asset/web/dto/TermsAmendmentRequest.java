package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import de.makibytes.registerwerk.deployment.api.schedule.BusinessDayConvention;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;

/**
 * Request body for {@code POST /api/v1/assets/{assetId}/terms-amendments}. Every term field is
 * optional — only the ones sent are amended. {@code issueDate} / {@code maturityDate} amend the
 * asset and its bond terms together so the two never diverge.
 *
 * @param legalReference the legal basis for the change (e.g. SchVG §5 creditor resolution of
 *                       2026-05-04, or "correction of clerical error per issuer letter …")
 */
public record TermsAmendmentRequest(
        // Asset-level economic terms
        String isin,
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter ISO-4217 code") String currency,
        @Positive BigDecimal issueSize,
        @Positive BigDecimal denomination,
        LocalDate issueDate,
        LocalDate maturityDate,
        @Positive BigDecimal minInvestmentAmount,
        @Positive BigDecimal maxHoldingAmount,
        // Bond terms
        @Positive @Digits(integer = 20, fraction = 18) BigDecimal faceValue,
        @DecimalMin("0") @Digits(integer = 2, fraction = 8) BigDecimal couponRate,
        @Size(max = 32) String referenceRate,
        @Digits(integer = 2, fraction = 8) BigDecimal spread,
        DayCountConvention dayCount,
        PaymentFrequency paymentFrequency,
        BusinessDayConvention businessDayConvention,
        @Min(0) @Max(10) Integer recordDateOffsetBd,
        @Min(0) @Max(30) Integer announcementLeadBd,
        @Min(0) @Max(365) Integer interestGraceDays,
        @Min(0) @Max(365) Integer principalGraceDays,

        @NotBlank @Size(max = 500) String legalReference
) {
    @AssertTrue(message = "at least one term must be amended")
    public boolean isAnyTermPresent() {
        return Stream.of(isin, currency, issueSize, denomination, issueDate, maturityDate, minInvestmentAmount,
                        maxHoldingAmount, faceValue, couponRate, referenceRate, spread, dayCount, paymentFrequency,
                        businessDayConvention, recordDateOffsetBd, announcementLeadBd, interestGraceDays,
                        principalGraceDays)
                .anyMatch(java.util.Objects::nonNull);
    }

    /** True when a field that lives on {@code AssetBondTerms} (or feeds the schedule) is amended. */
    public boolean touchesBondTerms() {
        return Stream.of(issueDate, maturityDate, faceValue, couponRate, referenceRate, spread, dayCount,
                        paymentFrequency, businessDayConvention, recordDateOffsetBd, announcementLeadBd,
                        interestGraceDays, principalGraceDays)
                .anyMatch(java.util.Objects::nonNull);
    }
}
