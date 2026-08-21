package de.makibytes.registerwerk.finality.api;

import java.util.Map;
import java.util.UUID;

/**
 * What a module tells {@link ChainEffectRecorder} about a state change it just made because of an
 * on-chain event, so it can be undone later if the source block is orphaned.
 *
 * <p>{@code beforeState}/{@code afterState} are opaque to the recorder and the dispatcher — only
 * the compensator that registered for {@code effectType} ever interprets them, and only
 * {@link CompensationCategory#INVERSE_FLIP} effects need to populate {@code beforeState} at all
 * (a {@link CompensationCategory#RECOMPUTE} effect re-derives its state from other still-current
 * rows and needs neither).
 *
 * @param chainConfigId which chain the source block belongs to
 * @param blockNumber   the block that caused this effect
 * @param blockHash     the block's hash when known (EVM); null for chains with no such concept
 * @param txHash        the transaction that caused this effect, when there is a single one
 * @param logIndex      the log index within the transaction, when applicable (EVM only)
 * @param moduleName    the recording module's name (e.g. {@code "indexer"}, {@code "orgidentity"}) —
 *                      informational, not used for routing (routing is by effectType alone, since
 *                      effect types are already namespaced by their owning module's vocabulary)
 * @param effectType    a stable identifier a {@link ChainEffectCompensator} registers against
 *                      (e.g. {@code "HOLDER_BALANCE_SYNCED"}, {@code "TX_COMPLETED"})
 * @param entityType    the affected entity's type name (e.g. {@code "Asset"}, {@code "OrgRegistration"})
 * @param entityId      the affected entity's id
 * @param category      how this effect is undone
 * @param beforeState   opaque pre-image, only meaningful for INVERSE_FLIP effects that cannot
 *                      re-derive their prior value; null otherwise
 * @param afterState    opaque post-image, purely informational/diagnostic; may be null
 * @param auditEventId  the {@code audit_event} row this effect's state change was recorded under,
 *                      if any — populates {@code AuditableEvent.reversesEventId()} on compensation
 * @param correlationId caller-supplied correlation id for tracing a single on-chain event across
 *                      multiple recorded effects; may be null
 */
public record ChainEffectDescriptor(
        UUID chainConfigId,
        long blockNumber,
        String blockHash,
        String txHash,
        Integer logIndex,
        String moduleName,
        String effectType,
        String entityType,
        UUID entityId,
        CompensationCategory category,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        UUID auditEventId,
        UUID correlationId) {
}
