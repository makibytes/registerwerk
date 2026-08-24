package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityPort.BlockFinalityRecord;
import de.makibytes.registerwerk.finality.api.ChainQuarantinedException;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.finality.api.ReorgEnvelopeConflictException;
import de.makibytes.registerwerk.finality.events.BlockFinalityChangedEvent;
import de.makibytes.registerwerk.finality.events.BlockRetractedEvent;
import de.makibytes.registerwerk.finality.events.ChainQuarantinedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlockFinalityServiceImpl — block_finality ledger unit tests")
class BlockFinalityServiceImplTest {

    @Mock private BlockFinalityRepository repository;
    @Mock private ChainEffectRepository chainEffectRepository;
    @Mock private CompensationDispatcher compensationDispatcher;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ReorgEpisodeStore reorgEpisodeStore;
    @Mock private ChainQuarantineStore chainQuarantineStore;

    private BlockFinalityServiceImpl service;
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlockFinalityServiceImpl(
                repository, chainEffectRepository, compensationDispatcher, eventPublisher, reorgEpisodeStore,
                chainQuarantineStore);
    }

    @Test
    @DisplayName("a new observation is saved and BlockFinalityChangedEvent is published")
    void recordObservation_newRow_savesAndPublishes() {
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.empty());
        when(repository.findByChainConfigIdAndBlockNumberAndBlockHash(chainConfigId, 100L, "0xhash100"))
                .thenReturn(Optional.empty());

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.SAFE);

        ArgumentCaptor<BlockFinality> captor = ArgumentCaptor.forClass(BlockFinality.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChainConfigId()).isEqualTo(chainConfigId);
        assertThat(captor.getValue().getBlockNumber()).isEqualTo(100L);
        assertThat(captor.getValue().getBlockHash()).isEqualTo("0xhash100");
        assertThat(captor.getValue().getLevel()).isEqualTo(FinalityLevel.SAFE);
        assertThat(captor.getValue().isCanonical()).isTrue();

        ArgumentCaptor<BlockFinalityChangedEvent> eventCaptor = ArgumentCaptor.forClass(BlockFinalityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().level()).isEqualTo(FinalityLevel.SAFE);
        assertThat(eventCaptor.getValue().blockNumber()).isEqualTo(100L);
    }

    @Test
    void isReorgRecordedDelegatesToDurableEpisodeStore() {
        ReorgObservation observation = reorg("episode-1", ReorgObservation.ReorgSeverity.ROUTINE);
        when(reorgEpisodeStore.replayStatus(chainConfigId, observation))
                .thenReturn(ReorgEpisodeStore.ReplayStatus.EXACT);

        assertThat(service.isReorgRecorded(chainConfigId, observation)).isTrue();
    }

    @Test
    void reusedReorgIdWithDifferentEnvelopeQuarantinesAndFailsClosed() {
        ReorgObservation observation = reorg("colliding-id", ReorgObservation.ReorgSeverity.ROUTINE);
        when(reorgEpisodeStore.replayStatus(chainConfigId, observation))
                .thenReturn(ReorgEpisodeStore.ReplayStatus.CONFLICT);

        assertThatThrownBy(() -> service.isReorgRecorded(chainConfigId, observation))
                .isInstanceOf(ReorgEnvelopeConflictException.class);

        verify(chainQuarantineStore).activate(eq(chainConfigId), eq(observation),
                eq(QuarantineTrigger.REORG_ID_COLLISION), any());
        verify(eventPublisher).publishEvent(any(ChainQuarantinedEvent.class));
    }

    @Test
    @DisplayName("promoting an existing row (SAFE -> FINALIZED) updates it and publishes once")
    void recordObservation_promotion_updatesAndPublishes() {
        BlockFinality existing = new BlockFinality();
        existing.setChainConfigId(chainConfigId);
        existing.setBlockNumber(100L);
        existing.setBlockHash("0xhash100");
        existing.setLevel(FinalityLevel.SAFE);
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.of(existing));

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.FINALIZED);

        assertThat(existing.getLevel()).isEqualTo(FinalityLevel.FINALIZED);
        verify(repository).save(existing);
        verify(eventPublisher, times(1)).publishEvent(any(BlockFinalityChangedEvent.class));
        // Reaching FINALIZED settles any still-ACTIVE chain_effect at this height - the unresolved-
        // effects set must not grow without bound once a block is trusted.
        verify(chainEffectRepository).settleAtBlock(chainConfigId, 100L, "0xhash100");
    }

    @Test
    @DisplayName("promoting to SAFE (not FINALIZED) does not settle any chain_effect")
    void recordObservation_promotionToSafe_doesNotSettle() {
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.empty());
        when(repository.findByChainConfigIdAndBlockNumberAndBlockHash(chainConfigId, 100L, "0xhash100"))
                .thenReturn(Optional.empty());

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.SAFE);

        verify(chainEffectRepository, never()).settleAtBlock(any(), anyLong(), any());
    }

    @Test
    @DisplayName("re-observing the same level is a no-op: no save, no event")
    void recordObservation_sameLevel_isNoOp() {
        BlockFinality existing = new BlockFinality();
        existing.setChainConfigId(chainConfigId);
        existing.setBlockNumber(100L);
        existing.setBlockHash("0xhash100");
        existing.setLevel(FinalityLevel.SAFE);
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.of(existing));

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.SAFE);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a transient probe regression (FINALIZED -> SAFE) never demotes the stored row")
    void recordObservation_demotion_isIgnored() {
        BlockFinality existing = new BlockFinality();
        existing.setChainConfigId(chainConfigId);
        existing.setBlockNumber(100L);
        existing.setBlockHash("0xhash100");
        existing.setLevel(FinalityLevel.FINALIZED);
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.of(existing));

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.SAFE);

        assertThat(existing.getLevel()).isEqualTo(FinalityLevel.FINALIZED);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("A -> B -> A can reactivate A, resets its finality, and preserves orphanedAt")
    void recordObservation_previousIncarnationReappears_reactivatesFromIncomingLevel() {
        BlockFinality existing = new BlockFinality();
        existing.setChainConfigId(chainConfigId);
        existing.setBlockNumber(100L);
        existing.setBlockHash("0xhashA");
        existing.setLevel(FinalityLevel.ORPHANED);
        existing.setCanonical(false);
        java.time.Instant orphanedAt = java.time.Instant.parse("2026-01-02T03:04:05Z");
        existing.setOrphanedAt(orphanedAt);
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.empty());
        when(repository.findByChainConfigIdAndBlockNumberAndBlockHash(chainConfigId, 100L, "0xhashA"))
                .thenReturn(Optional.of(existing));

        service.recordObservation(chainConfigId, 100L, "0xhashA", FinalityLevel.PROVISIONAL);

        assertThat(existing.isCanonical()).isTrue();
        assertThat(existing.getLevel()).isEqualTo(FinalityLevel.PROVISIONAL);
        assertThat(existing.getOrphanedAt()).isEqualTo(orphanedAt);
        verify(repository).save(existing);
        verify(eventPublisher).publishEvent(any(BlockFinalityChangedEvent.class));
    }

    @Test
    @DisplayName("a replacement hash is rejected while another incarnation remains canonical")
    void recordObservation_replacementWithoutRetraction_isRejectedBeforeMutation() {
        BlockFinality canonical = new BlockFinality();
        canonical.setChainConfigId(chainConfigId);
        canonical.setBlockNumber(100L);
        canonical.setBlockHash("0xhashA");
        canonical.setLevel(FinalityLevel.SAFE);
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.of(canonical));

        assertThatThrownBy(() -> service.recordObservation(
                chainConfigId, 100L, "0xhashB", FinalityLevel.PROVISIONAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without recording its retraction first");

        assertThat(canonical.getLevel()).isEqualTo(FinalityLevel.SAFE);
        assertThat(canonical.isCanonical()).isTrue();
        verify(repository, never()).save(any());
        verify(repository, never()).findByChainConfigIdAndBlockNumberAndBlockHash(any(), anyLong(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("hex hash case changes are the same incarnation, not a replacement")
    void recordObservation_mixedCaseHexHash_normalizesBeforeLookup() {
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 100L))
                .thenReturn(Optional.empty());
        when(repository.findByChainConfigIdAndBlockNumberAndBlockHash(chainConfigId, 100L, "0xabcdef"))
                .thenReturn(Optional.empty());

        service.recordObservation(chainConfigId, 100L, "0xAbCdEf", FinalityLevel.SAFE);

        ArgumentCaptor<BlockFinality> captor = ArgumentCaptor.forClass(BlockFinality.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getBlockHash()).isEqualTo("0xabcdef");
    }

    @Test
    @DisplayName("recordObservation rejects ORPHANED - only recordRetraction may set it")
    void recordObservation_rejectsOrphanedLevel() {
        assertThatThrownBy(() -> service.recordObservation(chainConfigId, 100L, "0xhash", FinalityLevel.ORPHANED))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("recordRetraction bulk-orphans and publishes BlockRetractedEvent exactly once")
    void recordRetraction_orphansAndPublishes() {
        when(repository.markOrphanedFromBlock(chainConfigId, 50L)).thenReturn(3);

        service.recordRetraction(chainConfigId, 50L, "0xnewcanonical", 4);

        verify(repository).markOrphanedFromBlock(chainConfigId, 50L);
        ArgumentCaptor<BlockRetractedEvent> captor = ArgumentCaptor.forClass(BlockRetractedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        BlockRetractedEvent event = captor.getValue();
        assertThat(event.chainConfigId()).isEqualTo(chainConfigId);
        assertThat(event.forkBlockNumber()).isEqualTo(50L);
        assertThat(event.replacementBlockHash()).isEqualTo("0xnewcanonical");
        assertThat(event.orphanedTransferCount()).isEqualTo(4);
        assertThat(event.actorId()).isNull();
        assertThat(event.actorRole()).isNull();
    }

    @Test
    @DisplayName("recordRetraction compensates every chain_effect at or after the fork block, in any status")
    void recordRetraction_compensatesEveryEffectAtOrAfterForkBlock() {
        UUID effectA = UUID.randomUUID();
        UUID effectB = UUID.randomUUID();
        when(chainEffectRepository.findIdsAtOrAfter(chainConfigId, 50L)).thenReturn(List.of(effectA, effectB));
        when(compensationDispatcher.compensate(effectA))
                .thenReturn(new CompensationOutcome.Compensated("undone"));
        when(compensationDispatcher.compensate(effectB))
                .thenReturn(new CompensationOutcome.NotApplicable("already undone"));

        service.recordRetraction(chainConfigId, 50L, "0xnewcanonical", 4);

        verify(compensationDispatcher).compensate(effectA);
        verify(compensationDispatcher).compensate(effectB);
    }

    @Test
    @DisplayName("legacy height-only retraction fails before mutation when it intersects a SETTLED effect")
    void recordRetraction_settledEffectFailsClosed() {
        when(chainEffectRepository.existsByChainConfigIdAndBlockNumberGreaterThanEqualAndStatus(
                chainConfigId, 50L, ChainEffect.Status.SETTLED)).thenReturn(true);

        assertThatThrownBy(() -> service.recordRetraction(chainConfigId, 50L, "0xnewcanonical", 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typed finality-violation episode");

        verify(repository, never()).markOrphanedFromBlock(any(), anyLong());
        verify(chainEffectRepository, never()).findIdsAtOrAfter(any(), anyLong());
        verify(compensationDispatcher, never()).compensate(any());
        verify(eventPublisher, never()).publishEvent(any(BlockRetractedEvent.class));
    }

    @Test
    @DisplayName("legacy height-only retraction also fails closed for a finalized block with no effects")
    void recordRetraction_finalizedBlockWithoutEffectsFailsClosed() {
        when(repository.existsByChainConfigIdAndBlockNumberGreaterThanEqualAndCanonicalTrueAndLevel(
                chainConfigId, 50L, FinalityLevel.FINALIZED)).thenReturn(true);

        assertThatThrownBy(() -> service.recordRetraction(chainConfigId, 50L, "0xnewcanonical", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("intersects finalized state");

        verify(repository, never()).markOrphanedFromBlock(any(), anyLong());
        verify(compensationDispatcher, never()).compensate(any());
    }

    @Test
    @DisplayName("an unverifiable self-probe retraction persists quarantine before any finalized mutation")
    void selfProbeFinalityConflictPersistsDurableQuarantine() {
        when(repository.existsByChainConfigIdAndBlockNumberGreaterThanEqualAndCanonicalTrueAndLevel(
                chainConfigId, 50L, FinalityLevel.FINALIZED)).thenReturn(true);
        when(reorgEpisodeStore.claim(eq(chainConfigId), any())).thenReturn(true);

        assertThat(service.quarantineUnverifiableFinalizedRetraction(
                chainConfigId, 50L, List.of("0xold"), "0xnew")).isTrue();

        ArgumentCaptor<ReorgObservation> episode = ArgumentCaptor.forClass(ReorgObservation.class);
        verify(reorgEpisodeStore).claim(eq(chainConfigId), episode.capture());
        assertThat(episode.getValue().severity())
                .isEqualTo(ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY);
        assertThat(episode.getValue().replacementLineage().getFirst().blockHash()).isEqualTo("0xnew");
        verify(chainQuarantineStore).activate(eq(chainConfigId), eq(episode.getValue()),
                eq(QuarantineTrigger.LOCAL_FINALITY_CONFLICT),
                org.mockito.ArgumentMatchers.contains("storedHashes=[0xold]"));
        verify(repository, never()).markOrphanedFromBlock(any(), anyLong());
        verify(chainEffectRepository, never()).findIdsAtOrAfter(any(), anyLong());
    }

    @Test
    @DisplayName("typed reorg applies exact block identities and their effects")
    void recordReorg_claimed_appliesExactOrphans() {
        ReorgObservation observation = reorg("episode-1", ReorgObservation.ReorgSeverity.ROUTINE);
        UUID effect = UUID.randomUUID();
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);
        when(repository.markCanonicalOrphanedByHashes(chainConfigId, List.of("0xaaa", "0xaab")))
                .thenReturn(2);
        when(chainEffectRepository.findIdsByBlockHashes(chainConfigId, List.of("0xaaa", "0xaab")))
                .thenReturn(List.of(effect));
        when(compensationDispatcher.compensate(effect))
                .thenReturn(new CompensationOutcome.Compensated("undone"));

        service.recordReorg(chainConfigId, observation, 7);

        verify(repository).markCanonicalOrphanedByHashes(chainConfigId, List.of("0xaaa", "0xaab"));
        verify(chainEffectRepository).findIdsByBlockHashes(chainConfigId, List.of("0xaaa", "0xaab"));
        verify(compensationDispatcher).compensate(effect);
        verify(repository, never()).markOrphanedFromBlock(any(), anyLong());
        ArgumentCaptor<BlockRetractedEvent> event = ArgumentCaptor.forClass(BlockRetractedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().forkBlockNumber()).isEqualTo(100L);
        assertThat(event.getValue().replacementBlockHash()).isEqualTo("0xbbb");
    }

    @Test
    @DisplayName("a failed routine-reorg compensation persists chain quarantine before ACK")
    void recordReorg_failedCompensation_quarantinesChain() {
        ReorgObservation observation = reorg("episode-compensation-failed", ReorgObservation.ReorgSeverity.ROUTINE);
        UUID failedEffect = UUID.randomUUID();
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);
        when(chainEffectRepository.findIdsByBlockHashes(chainConfigId, List.of("0xaaa", "0xaab")))
                .thenReturn(List.of(failedEffect));
        when(compensationDispatcher.compensate(failedEffect))
                .thenReturn(new CompensationOutcome.Failed("database unavailable", null));

        service.recordReorg(chainConfigId, observation, 0);

        verify(chainQuarantineStore).activate(chainConfigId, observation,
                QuarantineTrigger.DOMAIN_COMPENSATION_FAILED,
                "One or more domain chain-effect compensations failed or were irreversible");
        verify(eventPublisher).publishEvent(any(ChainQuarantinedEvent.class));
    }

    @Test
    @DisplayName("an upstream ROUTINE episode intersecting a locally SETTLED occurrence quarantines before mutation")
    void recordReorg_localFinalityPolicyDrift_quarantinesWithoutMutation() {
        ReorgObservation observation = reorg("episode-policy-drift", ReorgObservation.ReorgSeverity.ROUTINE);
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);
        when(chainEffectRepository.existsByChainConfigIdAndStatusAndBlockHashIn(
                chainConfigId, ChainEffect.Status.SETTLED, List.of("0xaaa", "0xaab")))
                .thenReturn(true);

        service.recordReorg(chainConfigId, observation, 0);

        verify(chainQuarantineStore).activate(chainConfigId, observation,
                QuarantineTrigger.LOCAL_FINALITY_CONFLICT,
                "Routine reorg intersects locally finalized/settled state");
        verify(repository, never()).markCanonicalOrphanedByHashes(any(), any());
        verify(compensationDispatcher, never()).compensate(any());
        verify(eventPublisher, never()).publishEvent(any(BlockRetractedEvent.class));
        verify(eventPublisher).publishEvent(any(ChainQuarantinedEvent.class));
    }

    @Test
    @DisplayName("an indexer-reported finalized occurrence quarantines before finality-ledger mutation")
    void recordReorg_indexerFinalityConflict_quarantinesWithoutMutation() {
        ReorgObservation observation = reorg("episode-indexer-policy-drift", ReorgObservation.ReorgSeverity.ROUTINE);
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);

        service.recordReorg(chainConfigId, observation, 0, true);

        verify(chainQuarantineStore).activate(chainConfigId, observation,
                QuarantineTrigger.LOCAL_FINALITY_CONFLICT,
                "Routine reorg intersects locally finalized/settled state");
        verify(repository, never()).markCanonicalOrphanedByHashes(any(), any());
        verify(compensationDispatcher, never()).compensate(any());
    }

    @Test
    @DisplayName("typed reorg preserves case-sensitive non-hex block identities")
    void recordReorg_nonHexIdentitiesAreExact() {
        ReorgObservation observation = new ReorgObservation(
                "1", "solana-case", ReorgObservation.ReorgSeverity.ROUTINE,
                new ReorgObservation.BlockReference(99L, "ParentABC", "GrandParent", FinalityLevel.SAFE),
                List.of(new ReorgObservation.BlockReference(
                        100L, "CaseSensitiveABC", "ParentABC", FinalityLevel.PROVISIONAL)),
                List.of(new ReorgObservation.BlockReference(
                        100L, "casesensitiveABC", "ParentABC", FinalityLevel.PROVISIONAL)),
                Instant.parse("2026-01-01T00:00:00Z"));
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);

        service.recordReorg(chainConfigId, observation, 0);

        verify(repository).markCanonicalOrphanedByHashes(chainConfigId, List.of("CaseSensitiveABC"));
        verify(chainEffectRepository).findIdsByBlockHashes(chainConfigId, List.of("CaseSensitiveABC"));
    }

    @Test
    @DisplayName("replayed typed episode is a total no-op")
    void recordReorg_replayed_doesNotMutateOrCompensate() {
        ReorgObservation observation = reorg("episode-1", ReorgObservation.ReorgSeverity.ROUTINE);
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(false);

        service.recordReorg(chainConfigId, observation, 0);

        verify(repository, never()).markCanonicalOrphanedByHashes(any(), any());
        verify(chainEffectRepository, never()).findIdsByBlockHashes(any(), any());
        verify(compensationDispatcher, never()).compensate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a finality violation persists quarantine but preserves canonical and business state")
    void recordReorg_finalityViolation_quarantinesWithoutMutation() {
        ReorgObservation observation = reorg(
                "episode-critical", ReorgObservation.ReorgSeverity.FINALITY_VIOLATION);
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);

        service.recordReorg(chainConfigId, observation, 0);

        verify(chainQuarantineStore).activate(chainConfigId, observation,
                QuarantineTrigger.CONSENSUS_FINALITY_VIOLATION, null);
        verify(eventPublisher).publishEvent(any(ChainQuarantinedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(BlockRetractedEvent.class));
        verify(repository, never()).markCanonicalOrphanedByHashes(any(), any());
        verify(chainEffectRepository, never()).findIdsByBlockHashes(any(), any());
        verify(compensationDispatcher, never()).compensate(any());
    }

    @Test
    @DisplayName("unresolved ancestry persists quarantine but does not guess a fork or mutate state")
    void recordReorg_unresolvedAncestry_quarantinesWithoutMutation() {
        ReorgObservation observation = reorg(
                "episode-unresolved", ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY);
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);

        service.recordReorg(chainConfigId, observation, 0);

        verify(chainQuarantineStore).activate(chainConfigId, observation,
                QuarantineTrigger.UNRESOLVED_ANCESTRY, null);
        verify(eventPublisher).publishEvent(any(ChainQuarantinedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(BlockRetractedEvent.class));
        verify(repository, never()).markCanonicalOrphanedByHashes(any(), any());
        verify(chainEffectRepository, never()).findIdsByBlockHashes(any(), any());
        verify(compensationDispatcher, never()).compensate(any());
    }

    @Test
    @DisplayName("an active quarantine rejects observations so durable consumers cannot acknowledge and drop them")
    void recordObservation_activeQuarantine_throwsBeforeMutation() {
        when(chainQuarantineStore.isActive(chainConfigId)).thenReturn(true);

        assertThatThrownBy(() -> service.recordObservation(
                chainConfigId, 102L, "0xbbb", FinalityLevel.PROVISIONAL))
                .isInstanceOf(ChainQuarantinedException.class);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("an active quarantine rejects routine reorgs and rolls their episode claim back")
    void recordReorg_activeQuarantine_throwsBeforeCanonicalMutation() {
        ReorgObservation observation = reorg("episode-parked", ReorgObservation.ReorgSeverity.ROUTINE);
        when(reorgEpisodeStore.claim(chainConfigId, observation)).thenReturn(true);
        when(chainQuarantineStore.isActive(chainConfigId)).thenReturn(true);

        assertThatThrownBy(() -> service.recordReorg(chainConfigId, observation, 0))
                .isInstanceOf(ChainQuarantinedException.class);

        verify(repository, never()).markCanonicalOrphanedByHashes(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("find() maps the entity to the public record")
    void find_mapsToPublicRecord() {
        BlockFinality row = new BlockFinality();
        row.setChainConfigId(chainConfigId);
        row.setBlockNumber(77L);
        row.setBlockHash("0xabc");
        row.setLevel(FinalityLevel.FINALIZED);
        when(repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, 77L))
                .thenReturn(Optional.of(row));

        Optional<BlockFinalityRecord> result = service.find(chainConfigId, 77L);

        assertThat(result).isPresent();
        assertThat(result.get().blockNumber()).isEqualTo(77L);
        assertThat(result.get().level()).isEqualTo(FinalityLevel.FINALIZED);
        assertThat(result.get().canonical()).isTrue();
    }

    @Test
    @DisplayName("findIncarnations() exposes ordered canonical and orphaned history")
    void findIncarnations_mapsEveryHistoricalRow() {
        BlockFinality hashA = new BlockFinality();
        hashA.setChainConfigId(chainConfigId);
        hashA.setBlockNumber(77L);
        hashA.setBlockHash("0xhashA");
        hashA.setLevel(FinalityLevel.ORPHANED);
        hashA.setCanonical(false);
        hashA.setOrphanedAt(java.time.Instant.parse("2026-01-02T03:04:05Z"));
        BlockFinality hashB = new BlockFinality();
        hashB.setChainConfigId(chainConfigId);
        hashB.setBlockNumber(77L);
        hashB.setBlockHash("0xhashB");
        hashB.setLevel(FinalityLevel.SAFE);
        when(repository.findByChainConfigIdAndBlockNumberOrderByObservedAtAscIdAsc(chainConfigId, 77L))
                .thenReturn(List.of(hashA, hashB));

        List<BlockFinalityRecord> result = service.findIncarnations(chainConfigId, 77L);

        assertThat(result).extracting(BlockFinalityRecord::blockHash)
                .containsExactly("0xhashA", "0xhashB");
        assertThat(result.get(0).canonical()).isFalse();
        assertThat(result.get(0).orphanedAt()).isNotNull();
        assertThat(result.get(1).canonical()).isTrue();
    }

    @Test
    @DisplayName("findBlocksWithUnresolvedEffects delegates straight to the repository")
    void findBlocksWithUnresolvedEffects_delegates() {
        when(chainEffectRepository.findDistinctUnresolvedBlockNumbers(chainConfigId)).thenReturn(List.of(10L, 20L));

        assertThat(service.findBlocksWithUnresolvedEffects(chainConfigId)).containsExactly(10L, 20L);
    }

    private static ReorgObservation reorg(String id, ReorgObservation.ReorgSeverity severity) {
        if (severity == ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY) {
            return new ReorgObservation(
                    "1",
                    id,
                    severity,
                    null,
                    List.of(),
                    List.of(
                            new ReorgObservation.BlockReference(
                                    101L, "0xbbc", "0xunknown", FinalityLevel.PROVISIONAL),
                            new ReorgObservation.BlockReference(
                                    102L, "0xbbd", "0xbbc", FinalityLevel.PROVISIONAL)),
                    Instant.parse("2026-01-01T00:00:00Z"));
        }
        ReorgObservation.BlockReference ancestor = new ReorgObservation.BlockReference(
                99L, "0x099", "0x098", FinalityLevel.SAFE);
        FinalityLevel orphanFinality = severity == ReorgObservation.ReorgSeverity.FINALITY_VIOLATION
                ? FinalityLevel.FINALIZED
                : FinalityLevel.SAFE;
        return new ReorgObservation(
                "1",
                id,
                severity,
                ancestor,
                List.of(
                        new ReorgObservation.BlockReference(100L, "0xaaa", "0x099", orphanFinality),
                        new ReorgObservation.BlockReference(101L, "0xaab", "0xaaa", orphanFinality)),
                List.of(
                        new ReorgObservation.BlockReference(100L, "0xbbb", "0x099", FinalityLevel.PROVISIONAL),
                        new ReorgObservation.BlockReference(101L, "0xbbc", "0xbbb", FinalityLevel.PROVISIONAL)),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
