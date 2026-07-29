package de.makibytes.registerwerk.lending.api;

/** Lifecycle of a cached borrower {@code lending_position} row. */
public enum LendingPositionStatus {
    /** Outstanding debt > 0 as of the last on-chain read. */
    OPEN,
    /** Fully repaid — the wallet has no outstanding debt on this market. */
    CLOSED,
    /** Debt was fully closed via {@code EwpgRepoMarket.liquidate} rather than {@code repay}. */
    LIQUIDATED
}
