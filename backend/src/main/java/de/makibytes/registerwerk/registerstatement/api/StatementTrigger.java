package de.makibytes.registerwerk.registerstatement.api;

/**
 * The §19 eWpG events that oblige issuance of a register statement to a consumer
 * holder of a single-entry crypto security.
 */
public enum StatementTrigger {
    /** §19(2) no. 1 — after the initial entry in the holder's favour. */
    INITIAL_ENTRY,
    /** §19(2) no. 2 — after every change to register content concerning the holder. */
    CHANGE,
    /** §19(2) no. 3 — the at-least-annual statement. */
    ANNUAL,
    /** §19(1) — on demand, where needed to exercise the holder's rights. */
    ON_DEMAND
}
