package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChainEffectRecorderImpl — idempotent journal writes via native upsert")
class ChainEffectRecorderImplTest {

    @Mock private ChainEffectRepository repository;
    @Mock private CompensationDispatcher dispatcher;
    @Mock private JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ChainQuarantineStore chainQuarantineStore;
    @Mock private BlockFinalityRepository blockFinalityRepository;
    @Mock private ChainEffectCompensator compensator;

    private ChainEffectRecorderImpl recorder;

    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();

    private ChainEffectDescriptor descriptor() {
        return ChainEffectDescriptor.of(chainConfigId, 100L, "0xhash", null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE);
    }

    @BeforeEach
    void setUp() {
        recorder = new ChainEffectRecorderImpl(repository, dispatcher, jdbcTemplate, objectMapper,
                chainQuarantineStore, blockFinalityRepository);
        lenient().when(dispatcher.compensatorFor("HOLDER_BALANCE_SYNCED")).thenReturn(Optional.of(compensator));
        lenient().when(compensator.category()).thenReturn(CompensationCategory.RECOMPUTE);
        lenient().when(blockFinalityRepository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(any(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(blockFinalityRepository.findByChainConfigIdAndBlockNumberAndBlockHash(any(), anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a new descriptor is inserted and its id returned")
    void recordInsertsNewRow() {
        UUID insertedId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenReturn(insertedId);

        UUID id = recorder.record(descriptor());

        assertThat(id).isEqualTo(insertedId);
        verify(repository, never()).findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any());
    }

    @Test
    @DisplayName("an effect discovered after its exact block finalized is inserted directly into settled state")
    void observationBeforeEffectSettlesNewJournalRow() {
        BlockFinality finalized = new BlockFinality();
        finalized.setChainConfigId(chainConfigId);
        finalized.setBlockNumber(100L);
        finalized.setBlockHash("0xhash");
        finalized.setLevel(de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED);
        when(blockFinalityRepository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.of(finalized));
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenReturn(UUID.randomUUID());

        recorder.record(descriptor());

        verify(repository).settleAtBlock(chainConfigId, 100L, "0xhash");
    }

    @Test
    @DisplayName("a receipt-confirmed effect settles atomically even before the block stream catches up")
    void recordFinalizedSettlesWithoutIndependentBlockObservation() {
        UUID insertedId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenReturn(insertedId);

        assertThat(recorder.recordFinalized(descriptor())).isEqualTo(insertedId);

        verify(repository).settleAtBlock(chainConfigId, 100L, "0xhash");
    }

    @Test
    @DisplayName("ON CONFLICT DO NOTHING (no row returned) falls back to the existing row instead of failing")
    void conflictFallsBackToExistingRow() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        UUID existingId = UUID.randomUUID();
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.of(existingRow(existingId, descriptor())));

        UUID id = recorder.record(descriptor());

        assertThat(id).isEqualTo(existingId);
    }

    @Test
    @DisplayName("a conflict with no matching row anywhere fails loud instead of silently returning null")
    void conflictWithNoExistingRowThrows() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(descriptor()))
                .isInstanceOf(IllegalStateException.class);
    }

    private ChainEffect existingRow(UUID id, ChainEffectDescriptor descriptor) {
        ChainEffect row = new ChainEffect();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", id);
        row.setChainConfigId(descriptor.chainConfigId());
        row.setBlockNumber(descriptor.blockNumber());
        row.setBlockHash(descriptor.blockHash());
        row.setTxHash(descriptor.txHash());
        row.setLogIndex(descriptor.logIndex());
        row.setSourceEventKey(ChainEffectRecorderImpl.sourceEventKey(descriptor));
        row.setModuleName(descriptor.moduleName());
        row.setEffectType(descriptor.effectType());
        row.setEntityType(descriptor.entityType());
        row.setEntityId(descriptor.entityId());
        row.setAssetId(descriptor.assetId());
        row.setCategory(descriptor.category());
        row.setBeforeState(descriptor.beforeState());
        row.setAfterState(descriptor.afterState());
        row.setAuditEventId(descriptor.auditEventId());
        row.setCorrelationId(descriptor.correlationId());
        row.setStatus(ChainEffect.Status.ACTIVE);
        return row;
    }

    @Test
    @DisplayName("recordAndCompensate records then immediately dispatches compensation for the resulting id")
    void recordAndCompensateDispatchesImmediately() {
        UUID insertedId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenReturn(insertedId);
        when(dispatcher.compensate(insertedId)).thenReturn(new CompensationOutcome.Compensated("ok"));

        CompensationOutcome outcome = recorder.recordAndCompensate(descriptor());

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        verify(dispatcher).compensate(insertedId);
    }

    @Test
    @DisplayName("source identity distinguishes block incarnations and preserves case-sensitive hashes")
    void sourceIdentityIsProtocolSafe() {
        ChainEffectDescriptor hexUpper = ChainEffectDescriptor.of(chainConfigId, 100L, "0xAABB", "0xCCDD",
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE);
        ChainEffectDescriptor hexLower = ChainEffectDescriptor.of(chainConfigId, 100L, "0xaabb", "0xccdd",
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE);
        ChainEffectDescriptor solanaUpper = ChainEffectDescriptor.of(chainConfigId, 100L, "AbC123", null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE);
        ChainEffectDescriptor solanaLower = ChainEffectDescriptor.of(chainConfigId, 100L, "abc123", null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE);

        assertThat(ChainEffectRecorderImpl.sourceEventKey(hexUpper))
                .isEqualTo(ChainEffectRecorderImpl.sourceEventKey(hexLower));
        assertThat(ChainEffectRecorderImpl.sourceEventKey(solanaUpper))
                .isNotEqualTo(ChainEffectRecorderImpl.sourceEventKey(solanaLower));
    }

    @Test
    @DisplayName("same idempotency key with different immutable semantics fails closed")
    void conflictWithDifferentSemanticsFailsClosed() {
        ChainEffectDescriptor descriptor = descriptor();
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        ChainEffect different = existingRow(UUID.randomUUID(), descriptor);
        different.setAssetId(UUID.randomUUID());
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.of(different));

        assertThatThrownBy(() -> recorder.record(descriptor))
                .isInstanceOf(ChainEffectConflictException.class);
        verify(repository, never()).reactivateCompensated(any());
    }

    @Test
    @DisplayName("persisted JSON number widths do not turn an exact replay into an idempotency conflict")
    void exactReplayComparesJsonSemanticsRatherThanJavaNumberTypes() {
        ChainEffectDescriptor descriptor = new ChainEffectDescriptor(
                chainConfigId, 100L, "0xhash", "0xtx", null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE,
                Map.of("blockNumber", 400L), Map.of("nested", Map.of("height", 401L)),
                null, null);
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        ChainEffect persisted = existingRow(UUID.randomUUID(), descriptor);
        persisted.setBeforeState(Map.of("blockNumber", 400));
        persisted.setAfterState(Map.of("nested", Map.of("height", 401)));
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.of(persisted));

        assertThat(recorder.record(descriptor)).isEqualTo(persisted.getId());
    }

    @Test
    @DisplayName("forward reapplication re-arms a compensated row, but retraction replay does not")
    void canonicalReturnReactivatesOnlyForwardRecord() {
        ChainEffectDescriptor descriptor = descriptor();
        UUID existingId = UUID.randomUUID();
        ChainEffect compensated = existingRow(existingId, descriptor);
        compensated.setStatus(ChainEffect.Status.COMPENSATED);
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.of(compensated));
        when(dispatcher.compensate(existingId)).thenReturn(new CompensationOutcome.NotApplicable("already done"));

        assertThat(recorder.record(descriptor)).isEqualTo(existingId);
        verify(repository).reactivateCompensated(existingId);

        CompensationOutcome replay = recorder.recordAndCompensate(descriptor);
        assertThat(replay).isInstanceOf(CompensationOutcome.NotApplicable.class);
        verify(repository).reactivateCompensated(existingId);
    }

    @Test
    @DisplayName("identity-less synthetic effects require an occurrence correlation id")
    void identityLessEffectWithoutOccurrenceIsRejected() {
        ChainEffectDescriptor identityLess = ChainEffectDescriptor.of(chainConfigId, 100L, null, null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE);

        assertThatThrownBy(() -> recorder.record(identityLess))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
        verify(chainQuarantineStore, never()).lockChain(any());
    }

    @Test
    @DisplayName("a transaction hash without a block incarnation is rejected")
    void transactionOnlyIdentityIsRejected() {
        ChainEffectDescriptor transactionOnly = ChainEffectDescriptor.of(
                chainConfigId, 100L, null, "0xtx", "orgidentity", "HOLDER_BALANCE_SYNCED",
                "Asset", entityId, entityId, CompensationCategory.RECOMPUTE);

        assertThatThrownBy(() -> recorder.record(transactionOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockHash");
        verify(chainQuarantineStore, never()).lockChain(any());
    }

    @Test
    @DisplayName("missing or category-mismatched compensator aborts instead of creating false recovery provenance")
    void compensatorContractIsRequiredAtWriteTime() {
        when(dispatcher.compensatorFor("HOLDER_BALANCE_SYNCED")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> recorder.record(descriptor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No ChainEffectCompensator");

        when(dispatcher.compensatorFor("HOLDER_BALANCE_SYNCED")).thenReturn(Optional.of(compensator));
        when(compensator.category()).thenReturn(CompensationCategory.INVERSE_FLIP);
        assertThatThrownBy(() -> recorder.record(descriptor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares category=INVERSE_FLIP");
        verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(UUID.class), any(Object[].class));
    }

    @Test
    @DisplayName("a known orphan incarnation is rejected after taking the chain serialization lock")
    void knownOrphanIsRejected() {
        BlockFinality orphan = new BlockFinality();
        orphan.setChainConfigId(chainConfigId);
        orphan.setBlockNumber(100L);
        orphan.setBlockHash("0xhash");
        orphan.setLevel(de.makibytes.registerwerk.finality.api.FinalityLevel.ORPHANED);
        orphan.setCanonical(false);
        when(blockFinalityRepository.findByChainConfigIdAndBlockNumberAndBlockHash(
                chainConfigId, 100L, "0xhash")).thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> recorder.record(descriptor()))
                .isInstanceOf(OrphanedChainEffectException.class);
        verify(chainQuarantineStore).lockChain(chainConfigId);
        verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(UUID.class), any(Object[].class));
    }

    @Test
    @DisplayName("synthetic reorg compensation is occurrence-bound and does not pretend null is a block hash")
    void syntheticRecordAndCompensateSkipsForwardCanonicalValidation() {
        BlockFinality canonical = new BlockFinality();
        canonical.setChainConfigId(chainConfigId);
        canonical.setBlockNumber(100L);
        canonical.setBlockHash("0xcanonical");
        canonical.setLevel(de.makibytes.registerwerk.finality.api.FinalityLevel.SAFE);
        lenient().when(blockFinalityRepository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.of(canonical));
        UUID occurrence = UUID.randomUUID();
        ChainEffectDescriptor synthetic = new ChainEffectDescriptor(
                chainConfigId, 100L, null, null, null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId, entityId,
                CompensationCategory.RECOMPUTE, null, Map.of("reorg", occurrence.toString()), null, occurrence);
        UUID insertedId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class), any(Object[].class)))
                .thenReturn(insertedId);
        when(dispatcher.compensate(insertedId)).thenReturn(new CompensationOutcome.Compensated("recomputed"));

        assertThat(recorder.recordAndCompensate(synthetic))
                .isInstanceOf(CompensationOutcome.Compensated.class);

        verify(blockFinalityRepository, never())
                .findByChainConfigIdAndBlockNumberAndCanonicalTrue(any(), anyLong());
        verify(dispatcher).compensate(insertedId);
    }
}
