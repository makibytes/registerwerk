package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.events.IndexerResetEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IndexerAdminService — manual recovery for a stuck indexer")
class IndexerAdminServiceTest {

    @Mock private IndexerStateRepository repository;
    @Mock private ApplicationEventPublisher events;

    private IndexerAdminService service;

    private IndexerState stuckState() {
        IndexerState s = new IndexerState();
        s.setId(UUID.randomUUID());
        s.setChainConfigId(UUID.randomUUID());
        s.setIndexerType(IndexerState.IndexerType.GRAPH_NODE);
        s.setStatus(IndexerState.IndexerStatus.ERROR);
        s.setConsecutiveErrors(10);
        s.setLastError("RPC down");
        s.setLastSyncedBlock(500L);
        s.setLastFinalBlock(488L);
        return s;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new IndexerAdminService(repository, events);
    }

    @Test
    @DisplayName("clears the error state and keeps the existing cursor when fullResync=false")
    void reset_clearsErrorState_keepsCursor() {
        IndexerState state = stuckState();
        when(repository.findById(state.getId())).thenReturn(Optional.of(state));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        UUID actorId = UUID.randomUUID();

        IndexerState result = service.reset(state.getId(), actorId, "REGISTRY_ADMIN", false);

        assertThat(result.getStatus()).isEqualTo(IndexerState.IndexerStatus.ACTIVE);
        assertThat(result.getConsecutiveErrors()).isEqualTo(0);
        assertThat(result.getLastError()).isNull();
        assertThat(result.getLastSyncedBlock()).isEqualTo(500L);
        assertThat(result.getLastFinalBlock()).isEqualTo(488L);

        ArgumentCaptor<IndexerResetEvent> captor = ArgumentCaptor.forClass(IndexerResetEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().indexerStateId()).isEqualTo(state.getId());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().fullResync()).isFalse();
    }

    @Test
    @DisplayName("also discards the cursor, forcing a full re-sync, when fullResync=true")
    void reset_discardsCursor_whenFullResync() {
        IndexerState state = stuckState();
        when(repository.findById(state.getId())).thenReturn(Optional.of(state));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        IndexerState result = service.reset(state.getId(), UUID.randomUUID(), "REGISTRY_ADMIN", true);

        assertThat(result.getLastSyncedBlock()).isNull();
        assertThat(result.getLastFinalBlock()).isNull();
        assertThat(result.getLastSyncedSignature()).isNull();
    }

    @Test
    @DisplayName("throws EntityNotFoundException for an unknown indexer state id")
    void reset_throwsWhenNotFound() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset(missingId, UUID.randomUUID(), "REGISTRY_ADMIN", false))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
