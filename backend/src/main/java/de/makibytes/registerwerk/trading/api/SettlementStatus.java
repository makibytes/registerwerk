package de.makibytes.registerwerk.trading.api;

public enum SettlementStatus {
    PENDING,
    SETTLED,
    /** The venue rejected the order, or a pending settlement timed out. Terminal — the trade
     *  never went through, but the attempt is preserved for reconciliation/audit. */
    FAILED,
    /** A PENDING trade was cancelled before it settled — nothing to reverse on-chain. */
    CANCELLED,
    /** A SETTLED trade was reversed after the fact (a compensating action, not modeled further
     *  here — see TradingService.refundSettledTrade). */
    REFUNDED
}
