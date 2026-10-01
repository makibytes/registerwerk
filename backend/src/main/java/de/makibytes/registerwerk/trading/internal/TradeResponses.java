package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.web.dto.TradeExecutionResponse;

import java.util.UUID;

/** Entity -> DTO mapping shared by the trading service and the operator queue. */
final class TradeResponses {

    private TradeResponses() {
    }

    /** {@code viewerEntityId == null} renders the operator's view (side "OPERATOR"). */
    static TradeExecutionResponse execution(UUID viewerEntityId, TradeExecution execution) {
        String side = viewerEntityId == null ? "OPERATOR"
                : viewerEntityId.equals(execution.getBuyerEntityId()) ? "BUY" : "SELL";
        return new TradeExecutionResponse(
                execution.getId(),
                side,
                execution.getListingId(),
                execution.getVenueCode(),
                execution.getAssetId(),
                execution.getAssetNumber(),
                execution.getAssetName(),
                execution.getIsin(),
                execution.getAssetType(),
                execution.getTokenStandard(),
                execution.getChain(),
                execution.getOrderType(),
                execution.getExecutedQuantity(),
                execution.getUnitPrice(),
                execution.getTotalPrice(),
                execution.getPaymentOption(),
                execution.getSettlementStatus(),
                execution.getWalletPreferenceMode(),
                execution.getWalletEndpointId(),
                execution.getWalletAddress(),
                execution.getCreatedAt(),
                execution.getSettledAt(),
                execution.getFailureReason(),
                execution.getPaymentReference(),
                execution.getPaymentDeclaredAt(),
                execution.isInstantSettlement(),
                execution.getDisputeReason(),
                execution.getUnresolvedAt(),
                // internal/operator-facing text: parties only see the status and the e-mail
                viewerEntityId == null ? execution.getUnresolvedReason() : null,
                // the cool-down is the buyer's business; the seller does not see it
                viewerEntityId != null && viewerEntityId.equals(execution.getBuyerEntityId())
                        ? execution.getBuyerCooldownUntil() : null,
                execution.getCurrency(),
                execution.getPaymentRailCode(),
                execution.getTotalPriceUnrounded(),
                execution.getPriceRoundingScale(),
                execution.getPriceRoundingMode(),
                execution.isRelatedParty(),
                execution.getRelatedPartyReasons());
    }
}
