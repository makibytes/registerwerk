package de.makibytes.registerwerk.asset.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * T3-07: primary-subscription orders that are still open (SUBMITTED / ALLOCATED) for an asset, for
 * the §20 eWpRV register handover package. Implemented by the asset module (orders are internal to it).
 */
public interface OpenSubscriptionOrdersPort {

    List<OpenOrder> openOrders(UUID assetId);

    record OpenOrder(UUID id, UUID investorEntityId, String walletAddress, BigDecimal requestedAmount,
                     BigDecimal allocatedAmount, String status) {
    }
}
