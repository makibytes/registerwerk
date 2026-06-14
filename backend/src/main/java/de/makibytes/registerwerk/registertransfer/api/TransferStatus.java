package de.makibytes.registerwerk.registertransfer.api;

/** Lifecycle of a §§21/22 eWpG register transfer to a successor operator. */
public enum TransferStatus {
    INITIATED,
    EXPORTED,
    HANDED_OVER,
    COMPLETED,
    CANCELLED
}
