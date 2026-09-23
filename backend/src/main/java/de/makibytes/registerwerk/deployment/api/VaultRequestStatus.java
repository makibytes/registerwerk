package de.makibytes.registerwerk.deployment.api;

public enum VaultRequestStatus {
    PENDING,
    FULFILLED,
    CANCELLED,
    /** Registry force-cancel on a legal basis ({@code ForcedRequestCancelled}): the escrow went to
     *  a destination named per case, not back to the payer/owner. */
    FORCE_CANCELLED
}
