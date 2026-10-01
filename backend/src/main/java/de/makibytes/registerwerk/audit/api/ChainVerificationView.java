package de.makibytes.registerwerk.audit.api;

import java.time.Instant;

/**
 * Result of a hash-chain integrity scan over the {@code audit_event} table.
 *
 * @param reason human-readable cause when {@code valid} is false (null otherwise)
 */
public record ChainVerificationView(
        boolean valid,
        long rowsChecked,
        Long firstBrokenSequenceNo,
        Instant checkedAt,
        String reason
) {
    public ChainVerificationView(boolean valid, long rowsChecked, Long firstBrokenSequenceNo, Instant checkedAt) {
        this(valid, rowsChecked, firstBrokenSequenceNo, checkedAt, null);
    }
}
