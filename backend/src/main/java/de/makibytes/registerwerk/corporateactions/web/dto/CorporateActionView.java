package de.makibytes.registerwerk.corporateactions.web.dto;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Issuer/investor-facing projection of a {@link CorporateAction} — unlike the raw entity the
 * operator admin controller returns, this deliberately omits {@code notes} (an internal operator
 * annotation field, e.g. cancellation reasons) and every actor id ({@code initiatedBy},
 * {@code issuerAttestedBy}, {@code dualControlApproverId}) — an issuer/investor needs to know
 * *whether* the two settlement-approval parties have signed off, not *who* on the operator side
 * did. Attestation/confirmation timestamps are kept (they're status, not identity).
 */
public record CorporateActionView(
        UUID id,
        UUID assetId,
        CorporateAction.ActionType actionType,
        CorporateAction.Status status,
        LocalDate announcementDate,
        LocalDate recordDate,
        LocalDate paymentDate,
        BigDecimal ratioNumerator,
        BigDecimal ratioDenominator,
        BigDecimal amountPerUnit,
        BigDecimal totalAmount,
        String currency,
        String settlementTxHash,
        Instant settledAt,
        Instant issuerAttestedAt,
        Instant dualControlApprovedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static CorporateActionView of(CorporateAction ca) {
        return new CorporateActionView(
                ca.getId(), ca.getAssetId(), ca.getActionType(), ca.getStatus(),
                ca.getAnnouncementDate(), ca.getRecordDate(), ca.getPaymentDate(),
                ca.getRatioNumerator(), ca.getRatioDenominator(), ca.getAmountPerUnit(), ca.getTotalAmount(),
                ca.getCurrency(), ca.getSettlementTxHash(), ca.getSettledAt(),
                ca.getIssuerAttestedAt(), ca.getDualControlApprovedAt(),
                ca.getCreatedAt(), ca.getUpdatedAt()
        );
    }
}
