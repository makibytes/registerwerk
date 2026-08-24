package de.makibytes.registerwerk.finality.api;

import java.util.UUID;

/**
 * Write port for the effect journal ({@code chain_effect}) — called by any module that just made
 * a state change because of an on-chain event, in the same transaction as that change.
 *
 * <p>Recording is idempotent: the same logical effect (same {@code sourceEventKey} derived from
 * the descriptor's chain/block identity/tx/logIndex, or its correlation occurrence for synthetic
 * effects, plus the same {@code effectType} and {@code entityId}) is
 * only ever stored once, so a caller does not need to check "did I already record this" itself.
 */
public interface ChainEffectRecorder {

    /**
     * Journals {@code descriptor} without attempting compensation. Use this for effects recorded
     * as they happen (normal, non-retraction flow) — the dispatcher only ever acts on a row when a
     * later retraction calls {@link #recordAndCompensate}, a retry job re-attempts an
     * already-ACTIVE row, or a caller compensates it directly.
     *
     * <p>If an exact source event was previously compensated and is then applied forward again
     * because its block became canonical again (A→B→A), the existing row is re-armed as ACTIVE
     * with a new journal sequence. Ordinary duplicate delivery while ACTIVE remains a no-op.
     *
     * @return the {@code chain_effect} row's id (existing id if this was already recorded)
     */
    UUID record(ChainEffectDescriptor descriptor);

    /**
     * Journals an effect after the owning module has already applied Registerwerk's FINALIZED
     * receipt policy. The exact occurrence is settled in the same transaction even when the
     * independent block-finality stream is lagging, so a reorg cannot be treated as routine in
     * the gap between business confirmation and a later block observation.
     */
    UUID recordFinalized(ChainEffectDescriptor descriptor);

    /**
     * Journals {@code descriptor} (idempotently, as {@link #record}) and immediately attempts
     * compensation in the same call — the path {@code ReorgGuard} uses when a block it just
     * discovered was already retracted turns out to have caused an effect that was recorded
     * before the retraction was known. For the common case (the effect is recorded once, then a
     * later retraction is what triggers compensation of an already-journalled row), callers use
     * {@link #record} followed by a separate compensation trigger instead.
     *
     * @return the outcome of the (attempted) compensation
     */
    CompensationOutcome recordAndCompensate(ChainEffectDescriptor descriptor);
}
