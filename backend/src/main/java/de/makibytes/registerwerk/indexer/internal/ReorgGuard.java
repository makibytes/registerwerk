package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Shared reorg-detection / finality-promotion logic used by every sync service that has a
 * two-tier PROVISIONAL/FINAL model (currently {@link GraphNodeSyncService} for EVM chains and
 * {@link StarknetTransferSyncService} for Starknet).
 *
 * <p>Deliberately probe-based rather than hash-based: EVM detects a fork by comparing a
 * previously-recorded block hash against a freshly re-fetched one, while Starknet detects it by
 * re-reading a transaction's {@code finality_status} (ACCEPTED_ON_L2 vs ACCEPTED_ON_L1 vs
 * REJECTED) — a completely different primitive with no block hash involved. Both shapes reduce
 * to the same three-way outcome (still provisional / now final / no longer canonical), which is
 * exactly what {@link FinalityProbe} expresses. Solana, Stellar, and Canton do not use this
 * class at all — see {@code TokenTransfer.FinalityStatus} javadoc for why each of those is
 * final-on-write and has no "provisional window" to re-verify.
 *
 * <p>Algorithm per call to {@link #reverifyProvisionalWindow}: walk the chain's PROVISIONAL
 * blocks in ascending order, probing each. On the first ORPHANED verdict, stop — every block at
 * or after that height is downstream of the fork and gets marked ORPHANED in one bulk update
 * (never deleted); the caller rewinds its cursor to re-index from there. On FINAL, flip that
 * block's rows to FINAL and continue. On UNKNOWN (the probe itself failed — e.g. a transient RPC
 * error), leave the block PROVISIONAL and continue to the next one; a single failed probe never
 * orphans anything, since that would turn a network blip into a false-positive audit event.
 */
@Component
class ReorgGuard {

    private static final Logger log = LoggerFactory.getLogger(ReorgGuard.class);

    enum ProbeResult { FINAL, PROVISIONAL, ORPHANED, UNKNOWN }

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

    record VerifyResult(int flippedFinal, int orphaned, Long forkBlock) {
        static final VerifyResult NONE = new VerifyResult(0, 0, null);
        boolean reorgDetected() { return forkBlock != null; }
    }

    private final TokenTransferRepository tokenTransferRepository;

    ReorgGuard(TokenTransferRepository tokenTransferRepository) {
        this.tokenTransferRepository = tokenTransferRepository;
    }

    @Transactional
    VerifyResult reverifyProvisionalWindow(UUID chainConfigId, FinalityProbe probe) {
        List<Long> provisionalBlocks = tokenTransferRepository.findDistinctProvisionalBlocks(chainConfigId);
        if (provisionalBlocks.isEmpty()) {
            return VerifyResult.NONE;
        }

        int flippedFinal = 0;
        for (long block : provisionalBlocks) {
            ProbeOutcome outcome;
            try {
                outcome = probe.probe(block);
            } catch (Exception e) {
                log.warn("ReorgGuard: probe failed for chainConfigId={} block={}: {}",
                        chainConfigId, block, e.getMessage());
                outcome = ProbeOutcome.unknown();
            }

            switch (outcome.result()) {
                case FINAL -> flippedFinal += tokenTransferRepository.markFinalAtBlock(chainConfigId, block);
                case PROVISIONAL, UNKNOWN -> {
                    // Leave as-is; re-checked again next tick. UNKNOWN never counts as a fork.
                }
                case ORPHANED -> {
                    boolean deepReorg = tokenTransferRepository.existsFinalAtOrAfter(chainConfigId, block);
                    int orphaned = tokenTransferRepository.markOrphanedFromBlock(chainConfigId, block);
                    if (deepReorg) {
                        log.error("ReorgGuard: CRITICAL — reorg at chainConfigId={} block={} reaches at or "
                                        + "below a previously-FINAL row. This is deeper than the configured "
                                        + "confirmation policy guarantees; {} row(s) orphaned including "
                                        + "previously-final ones. Manual review required.",
                                chainConfigId, block, orphaned);
                    } else {
                        log.warn("ReorgGuard: reorg detected at chainConfigId={} forkBlock={}; {} row(s) "
                                        + "marked ORPHANED.",
                                chainConfigId, block, orphaned);
                    }
                    return new VerifyResult(flippedFinal, orphaned, block);
                }
            }
        }
        return new VerifyResult(flippedFinal, 0, null);
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
