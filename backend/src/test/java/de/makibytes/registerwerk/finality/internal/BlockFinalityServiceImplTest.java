package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityPort.BlockFinalityRecord;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.events.BlockFinalityChangedEvent;
import de.makibytes.registerwerk.finality.events.BlockRetractedEvent;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    private BlockFinalityServiceImpl service;
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlockFinalityServiceImpl(repository, chainEffectRepository, compensationDispatcher, eventPublisher);
    }

    @Test
    @DisplayName("a new observation is saved and BlockFinalityChangedEvent is published")
    void recordObservation_newRow_savesAndPublishes() {
        when(repository.findByChainConfigIdAndBlockNumber(chainConfigId, 100L)).thenReturn(Optional.empty());

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.SAFE);

        ArgumentCaptor<BlockFinality> captor = ArgumentCaptor.forClass(BlockFinality.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChainConfigId()).isEqualTo(chainConfigId);
        assertThat(captor.getValue().getBlockNumber()).isEqualTo(100L);
        assertThat(captor.getValue().getBlockHash()).isEqualTo("0xhash100");
        assertThat(captor.getValue().getLevel()).isEqualTo(FinalityLevel.SAFE);

        ArgumentCaptor<BlockFinalityChangedEvent> eventCaptor = ArgumentCaptor.forClass(BlockFinalityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().level()).isEqualTo(FinalityLevel.SAFE);
        assertThat(eventCaptor.getValue().blockNumber()).isEqualTo(100L);
    }

    @Test
    @DisplayName("promoting an existing row (SAFE -> FINALIZED) updates it and publishes once")
    void recordObservation_promotion_updatesAndPublishes() {
        BlockFinality existing = new BlockFinality();
        existing.setChainConfigId(chainConfigId);
        existing.setBlockNumber(100L);
        existing.setBlockHash("0xhash100");
        existing.setLevel(FinalityLevel.SAFE);
        when(repository.findByChainConfigIdAndBlockNumber(chainConfigId, 100L)).thenReturn(Optional.of(existing));

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.FINALIZED);

        assertThat(existing.getLevel()).isEqualTo(FinalityLevel.FINALIZED);
        verify(repository).save(existing);
        verify(eventPublisher, times(1)).publishEvent(any(BlockFinalityChangedEvent.class));
        // Reaching FINALIZED settles any still-ACTIVE chain_effect at this height - the unresolved-
        // effects set must not grow without bound once a block is trusted.
        verify(chainEffectRepository).settleAtBlock(chainConfigId, 100L);
    }

    @Test
    @DisplayName("promoting to SAFE (not FINALIZED) does not settle any chain_effect")
    void recordObservation_promotionToSafe_doesNotSettle() {
        when(repository.findByChainConfigIdAndBlockNumber(chainConfigId, 100L)).thenReturn(Optional.empty());

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.SAFE);

        verify(chainEffectRepository, never()).settleAtBlock(any(), anyLong());
    }

    @Test
    @DisplayName("re-observing the same level is a no-op: no save, no event")
    void recordObservation_sameLevel_isNoOp() {
        BlockFinality existing = new BlockFinality();
        existing.setChainConfigId(chainConfigId);
        existing.setBlockNumber(100L);
        existing.setLevel(FinalityLevel.SAFE);
        when(repository.findByChainConfigIdAndBlockNumber(chainConfigId, 100L)).thenReturn(Optional.of(existing));

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
        existing.setLevel(FinalityLevel.FINALIZED);
        when(repository.findByChainConfigIdAndBlockNumber(chainConfigId, 100L)).thenReturn(Optional.of(existing));

        service.recordObservation(chainConfigId, 100L, "0xhash100", FinalityLevel.SAFE);

        assertThat(existing.getLevel()).isEqualTo(FinalityLevel.FINALIZED);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("ORPHANED is terminal: a normal observation never reverses it")
    void recordObservation_alreadyOrphaned_isIgnored() {
        BlockFinality existing = new BlockFinality();
        existing.setChainConfigId(chainConfigId);
        existing.setBlockNumber(100L);
        existing.setLevel(FinalityLevel.ORPHANED);
        when(repository.findByChainConfigIdAndBlockNumber(chainConfigId, 100L)).thenReturn(Optional.of(existing));

        service.recordObservation(chainConfigId, 100L, "0xnewhash", FinalityLevel.FINALIZED);

        assertThat(existing.getLevel()).isEqualTo(FinalityLevel.ORPHANED);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
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

        service.recordRetraction(chainConfigId, 50L, "0xnewcanonical", 4);

        verify(compensationDispatcher).compensate(effectA);
        verify(compensationDispatcher).compensate(effectB);
    }

    @Test
    @DisplayName("find() maps the entity to the public record")
    void find_mapsToPublicRecord() {
        BlockFinality row = new BlockFinality();
        row.setChainConfigId(chainConfigId);
        row.setBlockNumber(77L);
        row.setBlockHash("0xabc");
        row.setLevel(FinalityLevel.FINALIZED);
        when(repository.findByChainConfigIdAndBlockNumber(chainConfigId, 77L)).thenReturn(Optional.of(row));

        Optional<BlockFinalityRecord> result = service.find(chainConfigId, 77L);

        assertThat(result).isPresent();
        assertThat(result.get().blockNumber()).isEqualTo(77L);
        assertThat(result.get().level()).isEqualTo(FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("findBlocksWithUnresolvedEffects delegates straight to the repository")
    void findBlocksWithUnresolvedEffects_delegates() {
        when(chainEffectRepository.findDistinctUnresolvedBlockNumbers(chainConfigId)).thenReturn(List.of(10L, 20L));

        assertThat(service.findBlocksWithUnresolvedEffects(chainConfigId)).containsExactly(10L, 20L);
    }
}
