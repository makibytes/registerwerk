package de.makibytes.registerwerk.shared.api;

/**
 * Describes whether a blockchain-derived value is already available or still converging.
 */
public enum AsyncDataStatus {
    READY,
    PENDING,
    UPDATING
}
