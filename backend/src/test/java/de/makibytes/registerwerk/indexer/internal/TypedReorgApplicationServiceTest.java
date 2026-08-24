package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.indexer.api.TypedReorgCompensationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TypedReorgApplicationServiceTest {

    @Mock TokenTransferRepository transferRepository;
    @Mock IndexerStateRepository indexerStateRepository;
    @Mock ChainEffectRecorder effectRecorder;
    @Mock JdbcTemplate jdbcTemplate;

    @Test
    void orphansOnlyExplicitHashesAndRecomputesAffectedAssetsWithEpisodeCorrelation() {
        UUID chain = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        UUID reorgId = UUID.randomUUID();
        ReorgObservation observation = observation(reorgId.toString());
        when(transferRepository.findDistinctAssetIdsByBlockHashes(chain, List.of("0xaaa", "0xbbb")))
                .thenReturn(List.of(asset));
        when(transferRepository.markOrphanedByBlockHashes(chain, List.of("0xaaa", "0xbbb")))
                .thenReturn(3);
        when(effectRecorder.recordAndCompensate(any()))
                .thenReturn(new CompensationOutcome.Compensated("recomputed"));

        var result = service()
                .apply(chain, observation);

        assertThat(result.orphanedTransferCount()).isEqualTo(3);
        assertThat(result.affectedAssetIds()).containsExactly(asset);
        verify(indexerStateRepository).rewindBlockCursor(
                chain, IndexerState.IndexerType.GRAPH_NODE, 9L);
        verify(transferRepository, never()).markOrphanedFromBlock(any(), any());
        ArgumentCaptor<ChainEffectDescriptor> descriptor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(effectRecorder).recordAndCompensate(descriptor.capture());
        assertThat(descriptor.getValue().blockHash()).isNull();
        assertThat(descriptor.getValue().correlationId()).isEqualTo(reorgId);
        assertThat(descriptor.getValue().entityId()).isEqualTo(asset);
        assertThat(descriptor.getValue().afterState()).containsEntry("reorgId", reorgId.toString());
    }

    @Test
    void locallyFinalizedExactTransferBlocksRoutineMutation() {
        UUID chain = UUID.randomUUID();
        ReorgObservation observation = observation(UUID.randomUUID().toString());
        when(transferRepository.existsFinalizedByBlockHashes(chain, List.of("0xaaa", "0xbbb")))
                .thenReturn(true);

        var result = service()
                .apply(chain, observation);

        assertThat(result.blockedByLocalFinality()).isTrue();
        verify(transferRepository, never()).markOrphanedByBlockHashes(any(), any());
        verify(indexerStateRepository, never()).rewindBlockCursor(any(), any(), any());
        verify(effectRecorder, never()).recordAndCompensate(any());
    }

    @Test
    void failedHolderRecomputeRollsTypedApplicationBackViaDedicatedSignal() {
        UUID chain = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        ReorgObservation observation = observation(UUID.randomUUID().toString());
        when(transferRepository.findDistinctAssetIdsByBlockHashes(chain, List.of("0xaaa", "0xbbb")))
                .thenReturn(List.of(asset));
        when(effectRecorder.recordAndCompensate(any()))
                .thenReturn(new CompensationOutcome.Failed("projection database unavailable", null));

        assertThatThrownBy(() -> service().apply(chain, observation))
                .isInstanceOf(TypedReorgCompensationException.class)
                .hasMessageContaining("projection database unavailable");
    }

    @Test
    void unresolvedAncestryNeverGuessesAtTransferRows() {
        UUID chain = UUID.randomUUID();
        ReorgObservation unresolved = new ReorgObservation("1", UUID.randomUUID().toString(),
                ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY, null, List.of(),
                List.of(new ReorgObservation.BlockReference(
                        11, "0xreplacement", "0xparent", FinalityLevel.PROVISIONAL)), Instant.now());

        var result = service()
                .apply(chain, unresolved);

        assertThat(result.orphanedTransferCount()).isZero();
        verify(transferRepository, never()).markOrphanedByBlockHashes(any(), any());
        verify(effectRecorder, never()).recordAndCompensate(any());
        verify(indexerStateRepository, never()).rewindBlockCursor(any(), any(), any());
        verify(jdbcTemplate).query(
                eq("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"),
                any(org.springframework.jdbc.core.RowMapper.class), any());
    }

    @Test
    void finalityViolationQuarantinesElsewhereWithoutMutatingIndexerState() {
        UUID chain = UUID.randomUUID();
        var ancestor = new ReorgObservation.BlockReference(9, "0xparent", "0xgrand", FinalityLevel.FINALIZED);
        var orphan = new ReorgObservation.BlockReference(10, "0xold", "0xparent", FinalityLevel.FINALIZED);
        var replacement = new ReorgObservation.BlockReference(10, "0xnew", "0xparent", FinalityLevel.PROVISIONAL);
        var violation = new ReorgObservation("1", UUID.randomUUID().toString(),
                ReorgObservation.ReorgSeverity.FINALITY_VIOLATION, ancestor,
                List.of(orphan), List.of(replacement), Instant.now());

        var result = service()
                .apply(chain, violation);

        assertThat(result.orphanedTransferCount()).isZero();
        verify(transferRepository, never()).findDistinctAssetIdsByBlockHashes(any(), any());
        verify(transferRepository, never()).markOrphanedByBlockHashes(any(), any());
        verify(effectRecorder, never()).recordAndCompensate(any());
        verify(indexerStateRepository, never()).rewindBlockCursor(any(), any(), any());
    }

    @Test
    void preservesCaseSensitiveNonHexBlockIdentities() {
        UUID chain = UUID.randomUUID();
        var ancestor = new ReorgObservation.BlockReference(9, "ParentA", "GrandA", FinalityLevel.FINALIZED);
        var orphan = new ReorgObservation.BlockReference(10, "SoLanaBase58A", "ParentA", FinalityLevel.SAFE);
        var replacement = new ReorgObservation.BlockReference(10, "SoLanaBase58B", "ParentA", FinalityLevel.PROVISIONAL);
        var observation = new ReorgObservation("1", UUID.randomUUID().toString(),
                ReorgObservation.ReorgSeverity.ROUTINE, ancestor,
                List.of(orphan), List.of(replacement), Instant.now());
        when(transferRepository.findDistinctAssetIdsByBlockHashes(chain, List.of("SoLanaBase58A")))
                .thenReturn(List.of());

        service()
                .apply(chain, observation);

        verify(transferRepository).markOrphanedByBlockHashes(chain, List.of("SoLanaBase58A"));
    }

    @Test
    void firstPostGenesisForkRewindsGraphNodeCursorToGenesis() {
        UUID chain = UUID.randomUUID();
        var ancestor = new ReorgObservation.BlockReference(0, "0xparent", "0xgrand", FinalityLevel.FINALIZED);
        var orphan = new ReorgObservation.BlockReference(1, "0xold", "0xparent", FinalityLevel.SAFE);
        var replacement = new ReorgObservation.BlockReference(1, "0xnew", "0xparent", FinalityLevel.PROVISIONAL);
        var observation = new ReorgObservation("1", UUID.randomUUID().toString(),
                ReorgObservation.ReorgSeverity.ROUTINE, ancestor,
                List.of(orphan), List.of(replacement), Instant.now());

        service().apply(chain, observation);

        verify(indexerStateRepository).rewindBlockCursor(
                chain, IndexerState.IndexerType.GRAPH_NODE, 0L);
    }

    private TypedReorgApplicationService service() {
        return new TypedReorgApplicationService(
                transferRepository, indexerStateRepository, effectRecorder, jdbcTemplate);
    }

    private static ReorgObservation observation(String reorgId) {
        var ancestor = new ReorgObservation.BlockReference(9, "0xparent", "0xgrand", FinalityLevel.FINALIZED);
        var orphanA = new ReorgObservation.BlockReference(10, "0xAAA", "0xparent", FinalityLevel.SAFE);
        var orphanB = new ReorgObservation.BlockReference(11, "0xBBB", "0xAAA", FinalityLevel.PROVISIONAL);
        var replacement = new ReorgObservation.BlockReference(10, "0xCCC", "0xparent", FinalityLevel.PROVISIONAL);
        return new ReorgObservation("1", reorgId, ReorgObservation.ReorgSeverity.ROUTINE,
                ancestor, List.of(orphanA, orphanB), List.of(replacement), Instant.now());
    }
}
