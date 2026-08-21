package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.finality.api.FinalityLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * Published whenever a block's recorded finality level actually changes (PROVISIONAL→SAFE,
 * PROVISIONAL/SAFE→FINALIZED). Deliberately <b>not</b> an {@code AuditableEvent} — this fires on
 * routine, expected block progression (potentially many times a minute across several chains),
 * and the tamper-evident audit log is for state changes a compliance reviewer needs a permanent
 * record of, not for "block 100 is now considered safe". The state changes a block's progression
 * *causes* (a holder balance sync, a §19 statement) already get their own audit events at the
 * point they happen — see {@code HolderBalanceSyncedEvent} and friends.
 */
public record BlockFinalityChangedEvent(
        UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level, Instant occurredAt) {
}
