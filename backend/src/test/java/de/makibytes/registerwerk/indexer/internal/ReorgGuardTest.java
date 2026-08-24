package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.BlockFinalityPort;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReorgGuard — reverifyUnsettledWindow's severity grading, unsettled-block union, and compensation trigger")
class ReorgGuardTest {

    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private BlockFinalityFeed blockFinalityFeed;
    @Mock private BlockFinalityPort blockFinalityPort;
    @Mock private ChainEffectRecorder chainEffectRecorder;

    private ReorgGuard guard;
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guard = new ReorgGuard(tokenTransferRepository, blockFinalityFeed, blockFinalityPort,
                chainEffectRecorder, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("no unsettled blocks from either source means no probing and VerifyResult.NONE")
    void emptyUnsettledWindowDoesNothing() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of());
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of());

        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId, blockNumber -> {
            throw new AssertionError("probe should never be called");
        });

        assertThat(result.reorgDetected()).isFalse();
        assertThat(result).isEqualTo(ReorgGuard.VerifyResult.NONE);
    }

    @Test
    @DisplayName("a block flagged only by an unresolved chain_effect (no token_transfer row) is still probed")
    void unionsBlockFinalityPortBlocksIntoTheWalk() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of());
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of(42L));

        java.util.List<Long> probed = new java.util.ArrayList<>();
        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId, blockNumber -> {
            probed.add(blockNumber);
            return new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.PROVISIONAL, null);
        });

        assertThat(probed).containsExactly(42L);
        assertThat(result.reorgDetected()).isFalse();
    }

    @Test
    @DisplayName("FINALIZED promotes both PROVISIONAL and SAFE rows at that block and records the observation")
    void finalizedPromotesAndRecordsObservation() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(10L));
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of());
        when(tokenTransferRepository.markLevelAtBlock(chainConfigId, 10L, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED))
                .thenReturn(2);
        when(tokenTransferRepository.markLevelAtBlock(chainConfigId, 10L, FinalityLevel.SAFE, FinalityLevel.FINALIZED))
                .thenReturn(3);

        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId,
                blockNumber -> new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.FINALIZED, "0xhash"));

        assertThat(result.promotedFinalized()).isEqualTo(5);
        assertThat(result.reorgDetected()).isFalse();
        verify(blockFinalityFeed).recordObservation(chainConfigId, 10L, "0xhash", FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("SAFE promotes PROVISIONAL rows and records the observation")
    void safePromotesAndRecordsObservation() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(10L));
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of());
        when(tokenTransferRepository.markLevelAtBlock(chainConfigId, 10L, FinalityLevel.PROVISIONAL, FinalityLevel.SAFE))
                .thenReturn(4);

        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId,
                blockNumber -> new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.SAFE, "0xhash"));

        assertThat(result.promotedSafe()).isEqualTo(4);
        verify(blockFinalityFeed).recordObservation(chainConfigId, 10L, "0xhash", FinalityLevel.SAFE);
    }

    @Test
    @DisplayName("a probe that throws is treated as UNKNOWN — never counts as a fork, walk continues")
    void throwingProbeIsTreatedAsUnknownAndWalkContinues() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(10L, 11L));
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of());

        java.util.List<Long> probed = new java.util.ArrayList<>();
        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId, blockNumber -> {
            probed.add(blockNumber);
            if (blockNumber == 10L) {
                throw new RuntimeException("transient RPC error");
            }
            return new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.PROVISIONAL, null);
        });

        assertThat(probed).containsExactly(10L, 11L);
        assertThat(result.reorgDetected()).isFalse();
        verify(blockFinalityFeed, never()).recordRetraction(any(), anyLong(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("a shallow reorg (within PROVISIONAL, never reached SAFE/FINALIZED) stops the walk, "
            + "marks rows ORPHANED, and compensates every affected asset")
    void shallowReorgStopsWalkAndCompensates() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(10L, 11L));
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of());
        when(tokenTransferRepository.existsFinalizedAtOrAfter(chainConfigId, 10L)).thenReturn(false);
        when(tokenTransferRepository.existsSafeAtOrAfter(chainConfigId, 10L)).thenReturn(false);
        when(tokenTransferRepository.markOrphanedFromBlock(chainConfigId, 10L)).thenReturn(7);
        UUID assetA = UUID.randomUUID();
        UUID assetB = UUID.randomUUID();
        when(tokenTransferRepository.findDistinctAssetIdsAtOrAfter(chainConfigId, 10L))
                .thenReturn(List.of(assetA, assetB));
        when(chainEffectRecorder.recordAndCompensate(any())).thenReturn(new CompensationOutcome.Compensated("ok"));

        java.util.List<Long> probed = new java.util.ArrayList<>();
        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId, blockNumber -> {
            probed.add(blockNumber);
            return new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.ORPHANED, "0xnewhash");
        });

        // Only block 10 is probed — the walk stops at the first ORPHANED verdict instead of
        // continuing on to block 11, which is downstream of the fork by construction.
        assertThat(probed).containsExactly(10L);
        assertThat(result.reorgDetected()).isTrue();
        assertThat(result.forkBlock()).isEqualTo(10L);
        assertThat(result.orphaned()).isEqualTo(7);

        verify(blockFinalityFeed).recordRetraction(chainConfigId, 10L, "0xnewhash", 7);

        ArgumentCaptor<ChainEffectDescriptor> captor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder, times(2)).recordAndCompensate(captor.capture());
        assertThat(captor.getAllValues()).extracting(ChainEffectDescriptor::entityId)
                .containsExactlyInAnyOrder(assetA, assetB);
        assertThat(captor.getAllValues()).extracting(ChainEffectDescriptor::correlationId)
                .doesNotContainNull()
                .containsOnly(captor.getAllValues().getFirst().correlationId());
    }

    @Test
    @DisplayName("a reorg reaching an already-SAFE row is graded crosses_safe, not shallow")
    void reorgCrossingSafeIsGradedDifferentlyFromShallow() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(10L));
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of());
        when(tokenTransferRepository.existsFinalizedAtOrAfter(chainConfigId, 10L)).thenReturn(false);
        when(tokenTransferRepository.existsSafeAtOrAfter(chainConfigId, 10L)).thenReturn(true);
        when(tokenTransferRepository.markOrphanedFromBlock(chainConfigId, 10L)).thenReturn(1);
        when(tokenTransferRepository.findDistinctAssetIdsAtOrAfter(chainConfigId, 10L)).thenReturn(List.of());

        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId,
                blockNumber -> new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.ORPHANED, null));

        assertThat(result.reorgDetected()).isTrue();
    }

    @Test
    @DisplayName("a reorg reaching an already-FINALIZED row is graded crosses_finalized (CRITICAL)")
    void reorgCrossingFinalizedIsGradedCritical() {
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(10L));
        when(blockFinalityPort.findBlocksWithUnresolvedEffects(chainConfigId)).thenReturn(List.of());
        when(tokenTransferRepository.existsFinalizedAtOrAfter(chainConfigId, 10L)).thenReturn(true);
        when(tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, 10L))
                .thenReturn(List.of("0xold"));
        when(blockFinalityFeed.quarantineUnverifiableFinalizedRetraction(
                chainConfigId, 10L, List.of("0xold"), "0xnew")).thenReturn(true);

        ReorgGuard.VerifyResult result = guard.reverifyUnsettledWindow(chainConfigId,
                blockNumber -> new ReorgGuard.ProbeOutcome(ReorgGuard.ProbeResult.ORPHANED, "0xnew"));

        assertThat(result.reorgDetected()).isTrue();
        assertThat(result.orphaned()).isZero();
        verify(tokenTransferRepository, never()).markOrphanedFromBlock(any(), anyLong());
        verify(chainEffectRecorder, never()).recordAndCompensate(any());
        verify(blockFinalityFeed, never()).recordRetraction(
                any(), anyLong(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
