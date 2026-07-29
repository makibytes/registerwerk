package de.makibytes.registerwerk.audit.api;

import java.time.Instant;

/** Result of a hash-chain integrity scan over the {@code audit_event} table. */
public record ChainVerificationView(
        boolean valid,
        long rowsChecked,
        Long firstBrokenSequenceNo,
        Instant checkedAt
) {
}
