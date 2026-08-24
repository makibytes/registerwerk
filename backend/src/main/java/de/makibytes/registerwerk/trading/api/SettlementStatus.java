package de.makibytes.registerwerk.trading.api;

public enum SettlementStatus {
    PENDING,
    /** The buyer has declared payment (reference recorded) but the register has NOT yet been
     *  credited — the selling company must independently confirm receipt before the trade
     *  settles. Closes the prior gap where a buyer's unverified claim alone moved the register. */
    AWAITING_SELLER_CONFIRMATION,
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
