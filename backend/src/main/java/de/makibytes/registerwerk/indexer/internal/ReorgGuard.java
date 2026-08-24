package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.BlockFinalityPort;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Shared reorg-detection / finality-promotion logic used by every sync service that has a
 * three-tier PROVISIONAL/SAFE/FINALIZED model (currently {@link GraphNodeSyncService} for EVM
 * chains and {@link StarknetTransferSyncService} for Starknet).
 *
 * <p>Deliberately probe-based rather than hash-based: EVM detects a fork by comparing a
 * previously-recorded block hash against a freshly re-fetched one, while Starknet detects it by
 * re-reading a transaction's {@code finality_status} (ACCEPTED_ON_L2 vs ACCEPTED_ON_L1 vs
 * REJECTED) — a completely different primitive with no block hash involved. Both shapes reduce
 * to the same four-way outcome (still provisional / now safe / now finalized / no longer
 * canonical), which is exactly what {@link FinalityProbe} expresses. Solana, Stellar, and Canton
 * do not use this class at all — see {@link FinalityLevel}'s javadoc for why each of those is
 * final-on-write and has no "provisional window" to re-verify.
 *
 * <p>Algorithm per call to {@link #reverifyUnsettledWindow}: walk the chain's PROVISIONAL-or-SAFE
 * ("unsettled") blocks in ascending order, probing each — SAFE rows stay in the walk (not just
 * PROVISIONAL ones) precisely so they keep being re-probed until they reach FINALIZED, rather than
 * getting stuck at SAFE forever once nothing else ever re-visits them. On the first ORPHANED
 * verdict, stop — every block at or after that height is downstream of the fork and gets marked
 * ORPHANED in one bulk update (never deleted); the caller rewinds its cursor to re-index from
 * there. On SAFE or FINALIZED, promote that block's rows one step (see
 * {@link TokenTransferRepository#markLevelAtBlock} for why this stays monotonic) and continue. On
 * UNKNOWN (the probe itself failed — e.g. a transient RPC error), leave the block as-is and
 * continue to the next one; a single failed probe never orphans anything, since that would turn a
 * network blip into a false-positive audit event.
 *
 * <p>Every promotion and every retraction is also mirrored into {@link BlockFinalityFeed} — the
 * {@code finality} module's own ledger, so a reorg is no longer a dead end that only a passive
 * next-read filter on {@code token_transfer} notices (see {@code BlockRetractedEvent}'s javadoc).
 * An ORPHANED verdict additionally drives {@link ChainEffectRecorder} to recompensate every asset
 * with a transfer at or after the fork block — a reorg now actually corrects {@code asset_holder}
 * balances instead of only marking rows ORPHANED for a later reader to notice (see
 * {@link #compensateAffectedAssets}).
 */
@Component
class ReorgGuard {

    private static final Logger log = LoggerFactory.getLogger(ReorgGuard.class);

    /** {@code UNKNOWN} is deliberately not folded into {@link FinalityLevel} — it means the probe
     *  itself failed, not a lattice position, and the guarantee that a single failed probe never
     *  orphans anything depends on that being an explicit, separate case in an exhaustive switch. */
    enum ProbeResult { FINALIZED, SAFE, PROVISIONAL, ORPHANED, UNKNOWN }

    /** @param identity chain-specific identity token for this height (EVM: block hash), or null
     *                  when the chain type has no such concept (Starknet). Purely informational —
     *                  ReorgGuard itself does not compare identities across calls; each probe
     *                  implementation is responsible for its own comparison against whatever
     *                  baseline it was given (e.g. via {@link TokenTransferRepository#findDistinctBlockHashesAt}). */
    record ProbeOutcome(ProbeResult result, String identity) {
        static ProbeOutcome unknown() { return new ProbeOutcome(ProbeResult.UNKNOWN, null); }
    }

    @FunctionalInterface
    interface FinalityProbe {
        ProbeOutcome probe(long blockNumber);
    }

    record VerifyResult(int promotedSafe, int promotedFinalized, int orphaned, Long forkBlock) {
        static final VerifyResult NONE = new VerifyResult(0, 0, 0, null);
        boolean reorgDetected() { return forkBlock != null; }
    }

    private final TokenTransferRepository tokenTransferRepository;
    private final BlockFinalityFeed blockFinalityFeed;
    private final BlockFinalityPort blockFinalityPort;
    private final ChainEffectRecorder chainEffectRecorder;
    private final Counter reorgShallowCounter;
    private final Counter reorgCrossesSafeCounter;
    private final Counter reorgCrossesFinalizedCounter;

    ReorgGuard(TokenTransferRepository tokenTransferRepository, BlockFinalityFeed blockFinalityFeed,
            BlockFinalityPort blockFinalityPort, ChainEffectRecorder chainEffectRecorder, MeterRegistry meterRegistry) {
        this.tokenTransferRepository = tokenTransferRepository;
        this.blockFinalityFeed = blockFinalityFeed;
        this.blockFinalityPort = blockFinalityPort;
        this.chainEffectRecorder = chainEffectRecorder;
        this.reorgShallowCounter = Counter.builder("registerwerk.reorg.detected")
                .tag("severity", "shallow").description(
                        "Reorgs confined to the PROVISIONAL window — routine and expected, the whole point of the window")
                .register(meterRegistry);
        this.reorgCrossesSafeCounter = Counter.builder("registerwerk.reorg.detected")
                .tag("severity", "crosses_safe").description(
                        "Reorgs reaching an already-SAFE row — still within the confirmation policy's guarantee, but notable")
                .register(meterRegistry);
        this.reorgCrossesFinalizedCounter = Counter.builder("registerwerk.reorg.detected")
                .tag("severity", "crosses_finalized").description(
                        "Reorgs reaching an already-FINALIZED row — deeper than the configured confirmation policy guarantees; CRITICAL")
                .register(meterRegistry);
    }

    @Transactional
    VerifyResult reverifyUnsettledWindow(UUID chainConfigId, FinalityProbe probe) {
        // Merged with any block whose only activity is a not-yet-settled chain_effect (e.g. a
        // pause()/whitelist admin action with no correlated token_transfer row) — otherwise such a
        // block would sit entirely outside this walk and a reorg orphaning only it would go
        // undetected. See BlockFinalityPort#findBlocksWithUnresolvedEffects's javadoc.
        java.util.SortedSet<Long> unsettledBlocks = new java.util.TreeSet<>(
                tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId));
        unsettledBlocks.addAll(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId));
        if (unsettledBlocks.isEmpty()) {
            return VerifyResult.NONE;
        }

        int promotedSafe = 0;
        int promotedFinalized = 0;
        for (long block : unsettledBlocks) {
            ProbeOutcome outcome;
            try {
                outcome = probe.probe(block);
            } catch (Exception e) {
                log.warn("ReorgGuard: probe failed for chainConfigId={} block={}: {}",
                        chainConfigId, block, e.getMessage());
                outcome = ProbeOutcome.unknown();
            }

            switch (outcome.result()) {
                // Two calls: a block can reach FINALIZED directly from either PROVISIONAL (if the
                // depth/tag jumped past SAFE between ticks) or from SAFE (the normal case — a row
                // that was promoted to SAFE on an earlier tick and is still in this walk).
                case FINALIZED -> {
                    promotedFinalized += tokenTransferRepository.markLevelAtBlock(
                                chainConfigId, block, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED)
                            + tokenTransferRepository.markLevelAtBlock(
                                chainConfigId, block, FinalityLevel.SAFE, FinalityLevel.FINALIZED);
                    blockFinalityFeed.recordObservation(chainConfigId, block, outcome.identity(), FinalityLevel.FINALIZED);
                }
                case SAFE -> {
                    promotedSafe += tokenTransferRepository.markLevelAtBlock(
                            chainConfigId, block, FinalityLevel.PROVISIONAL, FinalityLevel.SAFE);
                    blockFinalityFeed.recordObservation(chainConfigId, block, outcome.identity(), FinalityLevel.SAFE);
                }
                case PROVISIONAL, UNKNOWN -> {
                    // Leave as-is; re-checked again next tick. UNKNOWN never counts as a fork.
                }
                case ORPHANED -> {
                    // Graded severity: a reorg confined to the PROVISIONAL window is routine and
                    // expected (that's the whole point of the window); one reaching an
                    // already-SAFE row is a materially more serious event even though it is still
                    // within the confirmation policy's promise (SAFE is not final); one reaching
                    // an already-FINALIZED row is a consensus failure deeper than the chain's
                    // configured finality guarantee and needs manual review.
                    boolean crossesFinalized = tokenTransferRepository.existsFinalizedAtOrAfter(chainConfigId, block);
                    boolean crossesSafe = tokenTransferRepository.existsSafeAtOrAfter(chainConfigId, block);
                    List<String> storedHashes = tokenTransferRepository
                            .findDistinctBlockHashesAt(chainConfigId, block);
                    if (blockFinalityFeed.quarantineUnverifiableFinalizedRetraction(
                            chainConfigId, block, storedHashes, outcome.identity())) {
                        log.error("ReorgGuard: self-probe mismatch at chainConfigId={} block={} intersects "
                                        + "locally finalized state; projections retained under chain quarantine",
                                chainConfigId, block);
                        reorgCrossesFinalizedCounter.increment();
                        return new VerifyResult(promotedSafe, promotedFinalized, 0, block);
                    }
                    int orphaned = tokenTransferRepository.markOrphanedFromBlock(chainConfigId, block);
                    compensateAffectedAssets(chainConfigId, block);
                    if (crossesFinalized) {
                        log.error("ReorgGuard: CRITICAL — reorg at chainConfigId={} block={} reaches at or "
                                        + "below a previously-FINALIZED row. This is deeper than the configured "
                                        + "confirmation policy guarantees; {} row(s) orphaned including "
                                        + "previously-finalized ones. Manual review required.",
                                chainConfigId, block, orphaned);
                        reorgCrossesFinalizedCounter.increment();
                    } else if (crossesSafe) {
                        log.warn("ReorgGuard: reorg at chainConfigId={} forkBlock={} reaches an already-SAFE "
                                        + "row (still within the confirmation policy's guarantee, but deeper than "
                                        + "a purely-PROVISIONAL reorg); {} row(s) marked ORPHANED.",
                                chainConfigId, block, orphaned);
                        reorgCrossesSafeCounter.increment();
                    } else {
                        log.warn("ReorgGuard: reorg detected at chainConfigId={} forkBlock={}; {} row(s) "
                                        + "marked ORPHANED.",
                                chainConfigId, block, orphaned);
                        reorgShallowCounter.increment();
                    }
                    blockFinalityFeed.recordRetraction(chainConfigId, block, outcome.identity(), orphaned);
                    return new VerifyResult(promotedSafe, promotedFinalized, orphaned, block);
                }
            }
        }
        return new VerifyResult(promotedSafe, promotedFinalized, 0, null);
    }

    /** Journals a {@code HOLDER_BALANCE_SYNCED} chain effect for every asset with a transfer at or
     *  after the fork block and immediately compensates it — the effect journal's first real
     *  producer. {@code asset_holder} is a pure RECOMPUTE (see {@link HolderRecomputeCompensator}),
     *  so there is no snapshot to build here: journalling and undoing collapse into one call. Runs
     *  regardless of severity (shallow / crosses-SAFE / crosses-FINALIZED) — every one of those is
     *  still fully compensable by construction (the policy floors that would make an effect
     *  IRREVERSIBLE only apply to outward-facing documents, not balance aggregation); severity only
     *  changes whether {@code FinalityGateImpl}'s per-asset {@code UNRESOLVED_COMPENSATION} freeze
     *  additionally kicks in on these assets — it does, automatically, whenever a compensation here
     *  fails or escalates as irreversible. */
    private void compensateAffectedAssets(UUID chainConfigId, long forkBlock) {
        UUID reorgOccurrenceId = UUID.randomUUID();
        for (UUID assetId : tokenTransferRepository.findDistinctAssetIdsAtOrAfter(chainConfigId, forkBlock)) {
            ChainEffectDescriptor descriptor = new ChainEffectDescriptor(
                    chainConfigId, forkBlock, null, null, null,
                    "indexer", HolderRecomputeCompensator.EFFECT_TYPE, "Asset", assetId, assetId,
                    CompensationCategory.RECOMPUTE, null, null, null, reorgOccurrenceId);
            chainEffectRecorder.recordAndCompensate(descriptor);
        }
    }

    /** {@code <CHAIN>_<NETWORK>} → {@code CHAIN}, matching {@link de.makibytes.registerwerk.chain.api.Chain}
     *  enum names, so {@link de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties#confirmationsFor}
     *  can be reused as the single source of confirmation-depth policy (task requirement: do not
     *  invent a second per-chain confirmation knob). */
    static String chainNameFrom(String chainConfigIdentifier) {
        if (chainConfigIdentifier == null) {
            return null;
        }
        int underscore = chainConfigIdentifier.indexOf('_');
        return underscore < 0 ? chainConfigIdentifier : chainConfigIdentifier.substring(0, underscore);
    }
}
