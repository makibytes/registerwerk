package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.finality.events.ChainEffectAcknowledgedEvent;
import de.makibytes.registerwerk.finality.events.ChainQuarantineResolvedEvent;
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
    @Mock private ChainQuarantineStore quarantineStore;

    private FinalityJournalAdminService service;
    private final UUID actorId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FinalityJournalAdminService(repository, dispatcher, eventPublisher, quarantineStore);
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
        row.setAcknowledgedBy(actorId);
        row.setAcknowledgedAt(java.time.Instant.now());
        row.setAcknowledgeReason("Reviewed, accepting the discrepancy");
        when(repository.acknowledgeIfUnresolved(
                org.mockito.ArgumentMatchers.eq(id), any(), org.mockito.ArgumentMatchers.eq(actorId), any()))
                .thenReturn(1);
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

    @Test
    @DisplayName("quarantine resolution requires every chain compensation resolved or acknowledged")
    void resolveQuarantine_unacknowledgedFailure_refusesToUnfreeze() {
        UUID chainId = UUID.randomUUID();
        when(quarantineStore.findActive(chainId)).thenReturn(Optional.of(
                new de.makibytes.registerwerk.finality.api.ChainQuarantinePort.ActiveChainQuarantine(
                        chainId, "episode-1", ReorgObservation.ReorgSeverity.ROUTINE,
                        QuarantineTrigger.DOMAIN_COMPENSATION_FAILED, "failed effect",
                        java.time.Instant.now(), java.time.Instant.now())));
        when(repository.existsByChainConfigIdAndStatusInAndAcknowledgedAtIsNull(
                org.mockito.ArgumentMatchers.eq(chainId), any())).thenReturn(true);

        assertThatThrownBy(() -> service.resolveQuarantine(
                chainId, "incident reviewed", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unacknowledged");

        verify(quarantineStore, never()).resolve(any(), any());
        verify(eventPublisher, never()).publishEvent(any(ChainQuarantineResolvedEvent.class));
    }

    @Test
    @DisplayName("explicit resolution clears the snapshot and emits actor/reason audit evidence")
    void resolveQuarantine_resolvedEffects_unfreezesAndAudits() {
        UUID chainId = UUID.randomUUID();
        when(quarantineStore.findActive(chainId)).thenReturn(Optional.of(
                new de.makibytes.registerwerk.finality.api.ChainQuarantinePort.ActiveChainQuarantine(
                        chainId, "episode-1", ReorgObservation.ReorgSeverity.ROUTINE,
                        QuarantineTrigger.DOMAIN_COMPENSATION_FAILED, "reconciled effects",
                        java.time.Instant.now(), java.time.Instant.now())));
        when(quarantineStore.resolve(org.mockito.ArgumentMatchers.eq(chainId), any())).thenReturn(1);

        service.resolveQuarantine(chainId, "  all effects reconciled  ", actorId, "REGISTRY_ADMIN");

        verify(quarantineStore).resolve(org.mockito.ArgumentMatchers.eq(chainId), any());
        ArgumentCaptor<ChainQuarantineResolvedEvent> event =
                ArgumentCaptor.forClass(ChainQuarantineResolvedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reorgId()).isEqualTo("episode-1");
        assertThat(event.getValue().reason()).isEqualTo("all effects reconciled");
        assertThat(event.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("generic acknowledgement cannot clear a quarantine with unreconciled canonical state")
    void resolveQuarantine_consensusIncidentRequiresDedicatedReconciliation() {
        UUID chainId = UUID.randomUUID();
        when(quarantineStore.findActive(chainId)).thenReturn(Optional.of(
                new de.makibytes.registerwerk.finality.api.ChainQuarantinePort.ActiveChainQuarantine(
                        chainId, "episode-finalized", ReorgObservation.ReorgSeverity.FINALITY_VIOLATION,
                        QuarantineTrigger.CONSENSUS_FINALITY_VIOLATION, "finalized lineage changed",
                        java.time.Instant.now(), java.time.Instant.now())));

        assertThatThrownBy(() -> service.resolveQuarantine(
                chainId, "looks fine", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit canonical-state reconciliation");

        verify(repository, never()).existsByChainConfigIdAndStatusInAndAcknowledgedAtIsNull(any(), any());
        verify(quarantineStore, never()).resolve(any(), any());
        verify(eventPublisher, never()).publishEvent(any(ChainQuarantineResolvedEvent.class));
    }
}
