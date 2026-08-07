package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.internal.SubscriptionOrder;

import java.math.BigDecimal;
import java.time.Instant;
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
        String rejectionReason
) {
    public static SubscriptionOrderResponse from(SubscriptionOrder o) {
        return new SubscriptionOrderResponse(
                o.getId(), o.getAssetId(), o.getInvestorEntityId(), o.getWalletAddress(),
                o.getRequestedAmount(), o.getAllocatedAmount(), o.getStatus().name(),
                o.getSubmittedAt(), o.getAllocatedAt(), o.getAllocatedBy(),
                o.getConfirmedAt(), o.getResultingHolderId(), o.getRejectionReason());
    }
}
