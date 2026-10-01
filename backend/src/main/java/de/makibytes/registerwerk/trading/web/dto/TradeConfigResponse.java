package de.makibytes.registerwerk.trading.web.dto;

/** Tenant-visible trading behaviour switches, so the desk can show honest copy and only offer what the backend honours. */
public record TradeConfigResponse(
        boolean demoInstantSettlementAvailable,
        int maxOpenReservationsPerBuyer,
        long reservationCooldownHours,
        long pendingTimeoutHours) {
}
