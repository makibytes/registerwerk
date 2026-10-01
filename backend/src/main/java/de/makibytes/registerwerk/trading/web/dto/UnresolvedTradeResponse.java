package de.makibytes.registerwerk.trading.web.dto;

import java.util.List;
import java.util.UUID;

/** One item of the operator's unresolved-payment queue (oldest first). */
public record UnresolvedTradeResponse(
        TradeExecutionResponse trade,
        UUID buyerEntityId,
        UUID sellerEntityId,
        long ageHours,
        boolean aged,
        List<TradeNoteResponse> notes) {
}
