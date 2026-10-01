package de.makibytes.registerwerk.trading.api;

public enum SettlementStatus {
    PENDING,
    /** The buyer has declared payment (reference recorded) but the register has NOT yet been
     *  credited — the selling company must independently confirm receipt before the trade
     *  settles. Closes the prior gap where a buyer's unverified claim alone moved the register. */
    AWAITING_SELLER_CONFIRMATION,
    /** A declared payment that cannot be settled automatically: the seller disputed it, the
     *  seller never confirmed within the timeout, a settlement gate failed at confirm time, or
     *  the seller's register entry / the asset / a party became ineligible while cash may have
     *  moved (Phase 5, 5A-03). The units STAY reserved and the listing is NOT re-offered; only an
     *  operator (4-eyes) can move it on, to SETTLED or FAILED. Never auto-failed. */
    PAYMENT_UNRESOLVED,
    SETTLED,
    /** The venue rejected the order, or a pending settlement timed out. Terminal — the trade
     *  never went through, but the attempt is preserved for reconciliation/audit. */
    FAILED,
    /** A PENDING trade was cancelled before it settled — nothing to reverse on-chain. */
    CANCELLED,
    /** A SETTLED trade was reversed after the fact (a compensating action, not modeled further
     *  here — see TradingService.refundSettledTrade). */
    REFUNDED;

    /** Statuses whose units are reserved against the seller's holding and excluded from the
     *  listing's availability. PAYMENT_UNRESOLVED deliberately stays in this set. */
    public static final java.util.List<SettlementStatus> RESERVING =
            java.util.List.of(PENDING, AWAITING_SELLER_CONFIRMATION, PAYMENT_UNRESOLVED);
}
