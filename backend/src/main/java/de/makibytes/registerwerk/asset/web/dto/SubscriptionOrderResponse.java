package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.internal.SubscriptionOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SubscriptionOrderResponse(
        UUID id,
        UUID assetId,
        UUID investorEntityId,
        String walletAddress,
        BigDecimal requestedAmount,
        BigDecimal allocatedAmount,
        String status,
        Instant submittedAt,
        Instant allocatedAt,
        UUID allocatedBy,
        Instant confirmedAt,
        UUID resultingHolderId,
        String rejectionReason,
        Instant acceptedAt,
        Instant allocationExpiresAt,
        BigDecimal amountDue,
        String paymentCurrency,
        BigDecimal paidAmount,
        BigDecimal refundDue,
        String paymentReference,
        LocalDate paymentValueDate,
        Instant paymentConfirmedAt,
        Instant settledAt,
        UUID settlementTxId,
        Instant lapsedAt,
        String releaseReason
) {
    public static SubscriptionOrderResponse from(SubscriptionOrder o) {
        return new SubscriptionOrderResponse(
                o.getId(), o.getAssetId(), o.getInvestorEntityId(), o.getWalletAddress(),
                o.getRequestedAmount(), o.getAllocatedAmount(), o.getStatus().name(),
                o.getSubmittedAt(), o.getAllocatedAt(), o.getAllocatedBy(),
                o.getConfirmedAt(), o.getResultingHolderId(), o.getRejectionReason(),
                o.getAcceptedAt(), o.getAllocationExpiresAt(), o.getAmountDue(), o.getPaymentCurrency(),
                o.getPaidAmount(), o.getRefundDue(), o.getPaymentReference(), o.getPaymentValueDate(),
                o.getPaymentConfirmedAt(), o.getSettledAt(), o.getSettlementTxId(), o.getLapsedAt(),
                o.getReleaseReason());
    }
}
