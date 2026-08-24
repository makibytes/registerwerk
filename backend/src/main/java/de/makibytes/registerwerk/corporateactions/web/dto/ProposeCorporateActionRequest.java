package de.makibytes.registerwerk.corporateactions.web.dto;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An issuer's proposal for a new corporate action — {@code POST /api/v1/assets/{assetId}/corporate-actions}.
 * Only {@link CorporateAction.ActionType#DIVIDEND}, {@link CorporateAction.ActionType#SPLIT}, and
 * {@link CorporateAction.ActionType#CALL} are accepted; every other value is rejected by
 * {@code CorporateActionProposalValidator} with a "not yet supported" message, not by bean
 * validation here (the valid set is a product decision, not a structural constraint).
 *
 * <p>Per-type required fields (also enforced by the validator, which has the domain context —
 * e.g. a bond's {@code AssetBondTerms} for CALL — that this record alone doesn't):
 * <ul>
 *   <li>DIVIDEND — {@code amountPerUnit}, {@code currency}, {@code recordDate}, {@code paymentDate}
 *   <li>SPLIT — {@code ratioNumerator}, {@code ratioDenominator}, {@code recordDate}
 *   <li>CALL — {@code paymentDate} (the call date), and either {@code callScheduleIndex} (an
 *       index into the bond's own {@code AssetBondTerms.callSchedule} — the validator resolves
 *       the real {@code callDate}/{@code callPrice} from there, never trusting client-supplied
 *       values for a scheduled call) or a custom {@code amountPerUnit} (the call price) for a
 *       call not on the pre-captured schedule
 * </ul>
 *
 * @param callScheduleIndex 0-based index into {@code AssetBondTerms.callSchedule}; when present,
 *                          the validator overrides {@code paymentDate}/{@code amountPerUnit} with
 *                          that schedule entry's own {@code callDate}/{@code callPrice}
 */
public record ProposeCorporateActionRequest(
        @NotNull CorporateAction.ActionType actionType,
        LocalDate announcementDate,
        LocalDate recordDate,
        LocalDate paymentDate,
        BigDecimal amountPerUnit,
        @Size(max = 3) String currency,
        BigDecimal ratioNumerator,
        BigDecimal ratioDenominator,
        @PositiveOrZero Integer callScheduleIndex,
        @Size(max = 2000) String notes
) {
    @AssertTrue(message = "SPLIT requires both ratioNumerator and ratioDenominator, and forbids amountPerUnit/currency")
    public boolean isRatioShapeValid() {
        if (actionType != CorporateAction.ActionType.SPLIT) {
            return ratioNumerator == null && ratioDenominator == null;
        }
        return ratioNumerator != null && ratioDenominator != null
                && ratioNumerator.signum() > 0 && ratioDenominator.signum() > 0
                && amountPerUnit == null && currency == null;
    }

    @AssertTrue(message = "callScheduleIndex is only valid for CALL proposals")
    public boolean isCallScheduleIndexValid() {
        return callScheduleIndex == null || actionType == CorporateAction.ActionType.CALL;
    }
}
