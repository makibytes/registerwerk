package de.makibytes.registerwerk.audit.api;

import java.time.LocalDate;

/** A signed commitment to the audit chain tip, published outside the database when a sink is configured. */
public record AuditAnchor(LocalDate anchorDate, long sequenceNo, String entryHashHex, String sigHex) {
}
