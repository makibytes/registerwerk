package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.events.ChainEffectAcknowledgedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinalityJournalAdminService — unresolved-compensation queue, retry, acknowledge")
class FinalityJournalAdminServiceTest {

    @Mock private ChainEffectRepository repository;
    @Mock private CompensationDispatcher dispatcher;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FinalityJournalAdminService service;
    private final UUID actorId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FinalityJournalAdminService(repository, dispatcher, eventPublisher);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ChainEffect rowWithStatus(UUID id, ChainEffect.Status status) {
        ChainEffect row = new ChainEffect();
        ReflectionTestUtils.setField(row, "id", id);
        row.setAssetId(assetId);
        row.setEffectType("TX_COMPLETED");
        row.setEntityType("BlockchainTransaction");
        row.setEntityId(UUID.randomUUID());
        row.setCategory(CompensationCategory.INVERSE_FLIP);
        row.setStatus(status);
        return row;
    }

    @Test
    @DisplayName("listUnresolved maps every COMPENSATION_FAILED/IRREVERSIBLE_ESCALATED row to a view")
    void listUnresolved_mapsRows() {
        ChainEffect failed = rowWithStatus(UUID.randomUUID(), ChainEffect.Status.COMPENSATION_FAILED);
        ChainEffect escalated = rowWithStatus(UUID.randomUUID(), ChainEffect.Status.IRREVERSIBLE_ESCALATED);
        when(repository.findByStatusInOrderByRecordedAtDesc(any())).thenReturn(List.of(failed, escalated));

        List<ChainEffectView> views = service.listUnresolved();

        assertThat(views).hasSize(2);
        assertThat(views).extracting(ChainEffectView::status)
                .containsExactly("COMPENSATION_FAILED", "IRREVERSIBLE_ESCALATED");
    }

    @Test
    @DisplayName("retry delegates to CompensationDispatcher.compensate for an existing row")
    void retry_delegatesToDispatcher() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(true);
        when(dispatcher.compensate(id)).thenReturn(new CompensationOutcome.Compensated("ok"));

        CompensationOutcome outcome = service.retry(id);

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        verify(dispatcher).compensate(id);
    }

    @Test
    @DisplayName("retry on an unknown id throws EntityNotFoundException without calling the dispatcher")
    void retry_unknownId_throws() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.retry(id)).isInstanceOf(EntityNotFoundException.class);
        verify(dispatcher, never()).compensate(any());
    }

    @Test
    @DisplayName("acknowledge stamps acknowledgedBy/At/reason and publishes an audit event")
    void acknowledge_stampsAndPublishes() {
        UUID id = UUID.randomUUID();
        ChainEffect row = rowWithStatus(id, ChainEffect.Status.COMPENSATION_FAILED);
        when(repository.findById(id)).thenReturn(Optional.of(row));

        ChainEffectView view = service.acknowledge(id, "Reviewed, accepting the discrepancy", actorId, "REGISTRY_ADMIN");

        assertThat(view.acknowledgedBy()).isEqualTo(actorId);
        assertThat(view.acknowledgeReason()).isEqualTo("Reviewed, accepting the discrepancy");
        assertThat(view.acknowledgedAt()).isNotNull();
        ArgumentCaptor<ChainEffectAcknowledgedEvent> captor = ArgumentCaptor.forClass(ChainEffectAcknowledgedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().assetId()).isEqualTo(assetId);
        assertThat(captor.getValue().reason()).isEqualTo("Reviewed, accepting the discrepancy");
    }

    @Test
    @DisplayName("acknowledge on an already-resolved row throws IllegalArgumentException")
    void acknowledge_alreadyResolved_throws() {
        UUID id = UUID.randomUUID();
        ChainEffect row = rowWithStatus(id, ChainEffect.Status.COMPENSATED);
        when(repository.findById(id)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.acknowledge(id, "reason", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("acknowledge on an unknown id throws EntityNotFoundException")
    void acknowledge_unknownId_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(id, "reason", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
