package de.makibytes.registerwerk.audit.api;

import java.time.Instant;

/** Current chain tip: the completeness anchor printed in evidence exports. */
public record ChainTipView(Long sequenceNo, String entryHashHex, Instant updatedAt) {
}
