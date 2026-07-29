package de.makibytes.registerwerk.lending.api;

/** Lifecycle of a registered {@code lending_market} row. */
public enum LendingMarketStatus {
    /** Open for new borrows/supplies (subject to the on-chain contract's own state). */
    ACTIVE,
    /** Operator-paused for new borrowing — see {@code EwpgRepoMarket.setBorrowPaused}. */
    PAUSED,
    /** No longer offered in the frontend; existing on-chain positions are unaffected. */
    RETIRED
}
