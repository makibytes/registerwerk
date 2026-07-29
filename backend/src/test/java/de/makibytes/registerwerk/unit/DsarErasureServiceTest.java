package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.ErasureRequest;
import de.makibytes.registerwerk.customer.api.ErasureRequestRepository;
import de.makibytes.registerwerk.customer.api.ErasureRequestStatus;
import de.makibytes.registerwerk.customer.events.DsarErasureRequestedEvent;
import de.makibytes.registerwerk.customer.events.DsarErasureResolvedEvent;
import de.makibytes.registerwerk.customer.internal.DsarErasureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DsarErasureServiceTest {

    ErasureRequestRepository repository;
    AppUserRepository userRepository;
    ApplicationEventPublisher eventPublisher;
    DsarErasureService service;

    static final UUID ENTITY = UUID.randomUUID();
    static final UUID USER = UUID.randomUUID();
    static final UUID OPERATOR = UUID.randomUUID();
    static final UUID APPROVER = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ErasureRequestRepository.class);
        userRepository = mock(AppUserRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DsarErasureService(repository, userRepository, eventPublisher);
        // Emulate JPA assigning the generated id on persist.
        when(repository.save(any(ErasureRequest.class))).thenAnswer(inv -> {
            ErasureRequest r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    @Test
    void request_createsRequestWith30DayClockAndPublishesEvent_whenNoOpenExists() {
        when(repository.findFirstByEntityIdAndStatusInOrderByRequestedAtAsc(eq(ENTITY), anyList()))
                .thenReturn(Optional.empty());

        ErasureRequest req = service.request(ENTITY, USER);

        assertThat(req.getStatus()).isEqualTo(ErasureRequestStatus.REQUESTED);
        assertThat(req.getEntityId()).isEqualTo(ENTITY);
        assertThat(req.getRequestedByUserId()).isEqualTo(USER);
        // due ~30 days out (Art. 12(3)); allow scheduling slack
        long days = Duration.between(req.getRequestedAt(), req.getDueAt()).toDays();
        assertThat(days).isEqualTo(30);
        assertThat(req.getDueAt()).isAfter(Instant.now());
        verify(repository).save(any(ErasureRequest.class));
        verify(eventPublisher).publishEvent(any(DsarErasureRequestedEvent.class));
    }

    @Test
    void request_isIdempotent_returnsExistingOpenRequestWithoutSaving() {
        ErasureRequest existing = new ErasureRequest();
        existing.setEntityId(ENTITY);
        existing.setStatus(ErasureRequestStatus.REQUESTED);
        when(repository.findFirstByEntityIdAndStatusInOrderByRequestedAtAsc(eq(ENTITY), anyList()))
                .thenReturn(Optional.of(existing));

        ErasureRequest req = service.request(ENTITY, USER);

        assertThat(req).isSameAs(existing);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void complete_marksResolvedAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        ErasureRequest open = openRequest(id);
        when(repository.findById(id)).thenReturn(Optional.of(open));

        ErasureRequest resolved = service.complete(id, OPERATOR, "erased marketing prefs; kept KYC (GwG §8)", APPROVER);

        assertThat(resolved.getStatus()).isEqualTo(ErasureRequestStatus.COMPLETED);
        assertThat(resolved.getReviewedBy()).isEqualTo(OPERATOR);
        assertThat(resolved.getReviewedAt()).isNotNull();
        assertThat(resolved.getResolutionNote()).contains("KYC");
        verify(eventPublisher).publishEvent(any(DsarErasureResolvedEvent.class));
    }

    @Test
    void complete_tombstonesAppUserContactFieldsButNotAlreadyErasedOnes() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(openRequest(id)));

        AppUser realUser = new AppUser();
        realUser.setId(UUID.randomUUID());
        realUser.setFullName("Heinz Weber");
        realUser.setEmail("heinz.weber@example.de");
        realUser.setPasswordHash("bcrypt-hash");
        realUser.setEnabled(true);

        AppUser alreadyErasedUser = new AppUser();
        alreadyErasedUser.setId(UUID.randomUUID());
        alreadyErasedUser.setEmail("erased-" + alreadyErasedUser.getId() + "@erased.invalid");
        alreadyErasedUser.setEnabled(false);

        when(userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(ENTITY))
                .thenReturn(List.of(realUser, alreadyErasedUser));

        service.complete(id, OPERATOR, "erased contact details; kept register/KYC trail (eWpG/GwG retention)", APPROVER);

        assertThat(realUser.getFullName()).isEqualTo("[ERASED]");
        assertThat(realUser.getEmail()).endsWith("@erased.invalid");
        assertThat(realUser.getPasswordHash()).isNull();
        assertThat(realUser.isEnabled()).isFalse();
        verify(userRepository).save(realUser);
        verify(userRepository, never()).save(alreadyErasedUser); // idempotent — already tombstoned
    }

    @Test
    void reject_marksRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(openRequest(id)));

        ErasureRequest resolved = service.reject(id, OPERATOR, "all fields under statutory retention");

        assertThat(resolved.getStatus()).isEqualTo(ErasureRequestStatus.REJECTED);
    }

    @Test
    void resolve_rejectsAlreadyResolvedRequest() {
        UUID id = UUID.randomUUID();
        ErasureRequest done = openRequest(id);
        done.setStatus(ErasureRequestStatus.COMPLETED);
        when(repository.findById(id)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.complete(id, OPERATOR, "x", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already resolved");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void listOpen_delegatesToRepository() {
        when(repository.findByStatusInOrderByRequestedAtAsc(anyList())).thenReturn(List.of());
        assertThat(service.listOpen()).isEmpty();
        verify(repository).findByStatusInOrderByRequestedAtAsc(anyList());
    }

    private static ErasureRequest openRequest(UUID id) {
        ErasureRequest r = new ErasureRequest();
        r.setEntityId(ENTITY);
        r.setStatus(ErasureRequestStatus.REQUESTED);
        r.setRequestedAt(Instant.now());
        r.setDueAt(Instant.now().plus(Duration.ofDays(30)));
        return r;
    }
}
