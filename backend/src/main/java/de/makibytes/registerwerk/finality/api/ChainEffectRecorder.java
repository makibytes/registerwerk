package de.makibytes.registerwerk.finality.api;

import java.util.UUID;

/**
 * Write port for the effect journal ({@code chain_effect}) — called by any module that just made
 * a state change because of an on-chain event, in the same transaction as that change.
 *
 * <p>Recording is idempotent: the same logical effect (same {@code sourceEventKey} derived from
 * the descriptor's chain/block/tx/logIndex, same {@code effectType}, same {@code entityId}) is
 * only ever stored once, so a caller does not need to check "did I already record this" itself.
 */
public interface ChainEffectRecorder {

    /**
     * Journals {@code descriptor} without attempting compensation. Use this for effects recorded
     * as they happen (normal, non-retraction flow) — the dispatcher only ever acts on a row when a
     * later retraction calls {@link #recordAndCompensate}, a retry job re-attempts an
     * already-ACTIVE row, or a caller compensates it directly.
     *
     * @return the {@code chain_effect} row's id (existing id if this was already recorded)
     */
    UUID record(ChainEffectDescriptor descriptor);

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
