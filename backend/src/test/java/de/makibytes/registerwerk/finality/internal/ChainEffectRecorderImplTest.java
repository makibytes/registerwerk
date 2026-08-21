package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChainEffectRecorderImpl — idempotent journal writes")
class ChainEffectRecorderImplTest {

    @Mock private ChainEffectRepository repository;
    @Mock private CompensationDispatcher dispatcher;

    private ChainEffectRecorderImpl recorder;

    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();

    private ChainEffectDescriptor descriptor() {
        return new ChainEffectDescriptor(chainConfigId, 100L, "0xhash", null, null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", entityId,
                CompensationCategory.RECOMPUTE, null, null, null, null);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        recorder = new ChainEffectRecorderImpl(repository, dispatcher);
    }

    @Test
    @DisplayName("a new descriptor is saved with a derived source_event_key")
    void recordSavesNewRow() {
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.empty());
        UUID savedId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> {
            ChainEffect row = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(row, "id", savedId);
            return row;
        });

        UUID id = recorder.record(descriptor());

        assertThat(id).isEqualTo(savedId);
        ArgumentCaptor<ChainEffect> captor = ArgumentCaptor.forClass(ChainEffect.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSourceEventKey()).isEqualTo(chainConfigId + ":100");
        assertThat(captor.getValue().getEffectType()).isEqualTo("HOLDER_BALANCE_SYNCED");
        assertThat(captor.getValue().getEntityId()).isEqualTo(entityId);
    }

    @Test
    @DisplayName("recording the same descriptor twice returns the existing id and does not save again")
    void recordIsIdempotent() {
        ChainEffect existing = new ChainEffect();
        UUID existingId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", existingId);
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        UUID id = recorder.record(descriptor());

        assertThat(id).isEqualTo(existingId);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a unique-constraint race falls back to the winning row instead of failing")
    void raceOnSaveFallsBackToWinner() {
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingRowWithId(UUID.randomUUID())));
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        UUID id = recorder.record(descriptor());

        assertThat(id).isNotNull();
    }

    private ChainEffect existingRowWithId(UUID id) {
        ChainEffect row = new ChainEffect();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    @Test
    @DisplayName("recordAndCompensate records then immediately dispatches compensation for the resulting id")
    void recordAndCompensateDispatchesImmediately() {
        when(repository.findBySourceEventKeyAndEffectTypeAndEntityId(any(), any(), any()))
                .thenReturn(Optional.empty());
        UUID savedId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> {
            ChainEffect row = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(row, "id", savedId);
            return row;
        });
        when(dispatcher.compensate(savedId)).thenReturn(new CompensationOutcome.Compensated("ok"));

        CompensationOutcome outcome = recorder.recordAndCompensate(descriptor());

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        verify(dispatcher).compensate(savedId);
    }
}
