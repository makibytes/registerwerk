package de.makibytes.registerwerk.audit.api;

import java.time.Instant;

/**
 * Result of a hash-chain integrity scan over the {@code audit_event} table.
 *
 * @param reason human-readable cause when {@code valid} is false (null otherwise)
 * @param status VALID, BROKEN (persisted; clears only after a later valid run AND an acknowledgement) or UNKNOWN (no run recorded)
 */
public record ChainVerificationView(
        boolean valid,
        long rowsChecked,
        Long firstBrokenSequenceNo,
        Instant checkedAt,
        String reason,
        String status,
        java.util.UUID verificationId
) {
    public ChainVerificationView(boolean valid, long rowsChecked, Long firstBrokenSequenceNo, Instant checkedAt) {
        this(valid, rowsChecked, firstBrokenSequenceNo, checkedAt, null, valid ? "VALID" : "BROKEN", null);
    }
}
