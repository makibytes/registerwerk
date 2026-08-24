package de.makibytes.registerwerk.idempotency.api;

public enum IdempotencyStatus {
    /** The original request for this key is still executing (or crashed mid-flight without
     *  completing — see {@code IdempotencyCleanupJob}). A concurrent duplicate must wait/fail,
     *  not double-execute the underlying action. */
    IN_PROGRESS,
    /** The original request finished with a response now safe to replay verbatim. */
    COMPLETED
}
