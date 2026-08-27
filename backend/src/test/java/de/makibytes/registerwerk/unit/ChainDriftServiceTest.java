package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.indexer.internal.ChainDriftEvent;
import de.makibytes.registerwerk.indexer.internal.ChainDriftEventRepository;
import de.makibytes.registerwerk.indexer.internal.ChainDriftService;
import de.makibytes.registerwerk.indexer.internal.ChainDriftSeverity;
import de.makibytes.registerwerk.indexer.internal.ChainDriftStatus;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainDriftServiceTest {

    @Mock private ChainDriftEventRepository repository;
    @Mock private ApplicationEventPublisher events;

    private ChainDriftService service;
    private final UUID eventId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ChainDriftService(repository, events);
    }

    private ChainDriftEvent openEvent() {
        ChainDriftEvent e = new ChainDriftEvent();
        e.setAssetId(UUID.randomUUID());
        e.setDeploymentId(UUID.randomUUID());
        e.setWalletAddress("0xabc");
        e.setDbBalance(new BigDecimal("100"));
        e.setOnchainBalance(new BigDecimal("90"));
        e.setSeverity(ChainDriftSeverity.WARNING);
        e.setStatus(ChainDriftStatus.OPEN);
        return e;
    }

    @Test
    @DisplayName("resolve() closes an OPEN case and publishes an audit event")
    void resolve_closesOpenCase() {
        ChainDriftEvent open = openEvent();
        when(repository.findById(eventId)).thenReturn(Optional.of(open));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChainDriftEvent resolved = service.resolve(eventId, actorId, "REGISTRY_ADMIN", "Registry corrected manually.");

        assertThat(resolved.getStatus()).isEqualTo(ChainDriftStatus.RESOLVED);
        assertThat(resolved.getResolvedBy()).isEqualTo(actorId);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolutionNotes()).isEqualTo("Registry corrected manually.");

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(de.makibytes.registerwerk.indexer.events.ChainDriftResolvedEvent.class);
    }

    @Test
    @DisplayName("resolve() rejects a case that is already RESOLVED — not reversible from here")
    void resolve_rejectsAlreadyResolved() {
        ChainDriftEvent resolved = openEvent();
        resolved.setStatus(ChainDriftStatus.RESOLVED);
        when(repository.findById(eventId)).thenReturn(Optional.of(resolved));

        assertThatThrownBy(() -> service.resolve(eventId, actorId, "REGISTRY_ADMIN", "n/a"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("resolve() on an unknown id throws EntityNotFoundException")
    void resolve_unknownId_throwsNotFound() {
        when(repository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(eventId, actorId, "REGISTRY_ADMIN", "n/a"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("countOpen() delegates to the repository's confirmed-only open count")
    void countOpen_delegates() {
        when(repository.countByStatusAndConfirmedTrue(ChainDriftStatus.OPEN)).thenReturn(3L);

        assertThat(service.countOpen()).isEqualTo(3L);
    }

    @Test
    @DisplayName("list(OPEN, null, ...) uses the confirmed-only finder — unconfirmed candidates "
            + "stay out of the operator work queue")
    void list_openStatus_usesConfirmedOnlyFinder() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(repository.findByStatusAndConfirmedTrueOrderByDetectedAtDesc(ChainDriftStatus.OPEN, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.list(ChainDriftStatus.OPEN, null, pageable);

        verify(repository).findByStatusAndConfirmedTrueOrderByDetectedAtDesc(ChainDriftStatus.OPEN, pageable);
        verify(repository, org.mockito.Mockito.never()).findByStatusOrderByDetectedAtDesc(any(), any());
    }

    @Test
    @DisplayName("list(RESOLVED, null, ...) uses the plain by-status finder — no confirmed gate on closed cases")
    void list_resolvedStatus_usesPlainFinder() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(repository.findByStatusOrderByDetectedAtDesc(ChainDriftStatus.RESOLVED, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.list(ChainDriftStatus.RESOLVED, null, pageable);

        verify(repository).findByStatusOrderByDetectedAtDesc(ChainDriftStatus.RESOLVED, pageable);
        verify(repository, org.mockito.Mockito.never()).findByStatusAndConfirmedTrueOrderByDetectedAtDesc(any(), any());
    }
}
