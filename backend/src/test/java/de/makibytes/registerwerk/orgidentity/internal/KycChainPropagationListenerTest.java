package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.kyc.events.KycExpiringEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.events.KycChainPropagationEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T2-19: a KYC expiry / rejection must reach the chain — org suspension plus KYC/AML claim
 * revocation (removeClaim + issuer-level revokeClaimBySignature via the Erc3643Api port) — and a
 * propagation row may only complete once both are confirmed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KycChainPropagationListener — KYC lapse reaches the chain")
class KycChainPropagationListenerTest {

    @Mock KycChainPropagationRepository propagationRepository;
    @Mock OrgRegistrationRepository registrationRepository;
    @Mock OrgRegistrationService registrationService;
    @Mock LegalEntityRepository legalEntityRepository;
    @Mock Erc3643Api erc3643Api;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meterRegistry;
    private KycChainPropagationListener listener;
    private final UUID entityId = UUID.randomUUID();
    private final UUID chainId = UUID.randomUUID();
    private final UUID registrationId = UUID.randomUUID();
    private final Map<UUID, KycChainPropagation> rows = new HashMap<>();
    private OrgRegistration registration;
    private LegalEntity entity;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = new KycChainPropagationListener(propagationRepository, registrationRepository,
                registrationService, legalEntityRepository, erc3643Api, eventPublisher,
                transactionManager, meterRegistry);

        // In-memory propagation table.
        when(propagationRepository.save(any(KycChainPropagation.class))).thenAnswer(inv -> {
            KycChainPropagation row = inv.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            rows.put(row.getId(), row);
            return row;
        });
        when(propagationRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(propagationRepository.findByLegalEntityIdAndChainConfigId(any(), any())).thenAnswer(inv ->
                rows.values().stream().filter(r -> r.getLegalEntityId().equals(inv.getArgument(0))
                        && r.getChainConfigId().equals(inv.getArgument(1))).findFirst());
        when(propagationRepository.findByStatusIn(any())).thenAnswer(inv -> rows.values().stream()
                .filter(r -> ((java.util.Collection<?>) inv.getArgument(0)).contains(r.getStatus())).toList());
        when(propagationRepository.countByStatus(any())).thenAnswer(inv ->
                rows.values().stream().filter(r -> r.getStatus() == inv.getArgument(0)).count());

        registration = new OrgRegistration();
        registration.setId(registrationId);
        registration.setLegalEntityId(entityId);
        registration.setChainConfigId(chainId);
        registration.setStatus(OrgRegistrationStatus.ACTIVE);
        when(registrationRepository.findByLegalEntityId(entityId)).thenReturn(List.of(registration));
        when(registrationRepository.findByLegalEntityIdAndChainConfigId(entityId, chainId))
                .thenReturn(Optional.of(registration));
        when(erc3643Api.identityChainIds(entityId)).thenReturn(List.of(chainId));

        entity = new LegalEntity();
        entity.setKycStatus(KycStatus.EXPIRED);
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
    }

    private KycChainPropagation onlyRow() {
        assertThat(rows).hasSize(1);
        return rows.values().iterator().next();
    }

    @Test
    @DisplayName("KYC expiry submits suspendOrg and claim revocation (removeClaim + revokeClaimBySignature)")
    void expiry_suspendsOrgAndRevokesClaims() {
        when(erc3643Api.revokeComplianceClaims(eq(entityId), eq(chainId), any(), eq("SYSTEM"), anyMap()))
                .thenReturn(2);

        listener.on(new KycExpiringEvent(entityId, null, Map.of("reason", "EXPIRED", "expiryDate", "2026-10-01")));

        verify(registrationService).suspend(eq(registrationId), anyString(), eq(null), eq("SYSTEM"));
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(erc3643Api).revokeComplianceClaims(eq(entityId), eq(chainId), eq(null), eq("SYSTEM"), audit.capture());
        assertThat(audit.getValue()).containsEntry("trigger", "KYC_EXPIRED");
        KycChainPropagation row = onlyRow();
        // submitted, not yet confirmed on chain
        assertThat(row.getStatus()).isEqualTo(KycChainPropagation.Status.PENDING);
        assertThat(row.getOrgAction()).isEqualTo(KycChainPropagationListener.ORG_SUSPEND_SUBMITTED);
        assertThat(row.getUnresolvedClaims()).isEqualTo(2);
    }

    @Test
    @DisplayName("the retry pass completes the row once suspension and revocations are confirmed — without resubmitting")
    void retry_completesOnceConfirmed() {
        when(erc3643Api.revokeComplianceClaims(any(), any(), any(), any(), anyMap())).thenReturn(1, 0);
        listener.on(new KycRejectedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of("reason", "x")));
        entity.setKycStatus(KycStatus.REJECTED);

        registration.setStatus(OrgRegistrationStatus.SUSPENDED); // poller confirmed suspendOrg
        listener.retryOpen();

        KycChainPropagation row = onlyRow();
        assertThat(row.getStatus()).isEqualTo(KycChainPropagation.Status.COMPLETED);
        assertThat(row.getTriggerReason()).isEqualTo(KycChainPropagationListener.TRIGGER_REJECTED);
        assertThat(row.getCompletedAt()).isNotNull();
        verify(registrationService, org.mockito.Mockito.times(1)).suspend(any(), any(), any(), any());
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue()).isInstanceOf(KycChainPropagationEvent.class);
        assertThat(((KycChainPropagationEvent) events.getValue()).payload()).containsEntry("status", "COMPLETED");

        listener.retryOpen(); // completed rows are not driven again
        verify(erc3643Api, org.mockito.Mockito.times(2)).revokeComplianceClaims(any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("EXPIRING_SOON warnings do not touch the chain")
    void expiringSoon_isIgnored() {
        listener.on(new KycExpiringEvent(entityId, null, Map.of("reason", "EXPIRING_SOON")));

        assertThat(rows).isEmpty();
        verifyNoInteractions(registrationService);
        verify(erc3643Api, never()).revokeComplianceClaims(any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("a re-approval before completion supersedes the row instead of suspending")
    void reapproved_supersedes() {
        entity.setKycStatus(KycStatus.APPROVED);

        listener.on(new KycExpiringEvent(entityId, null, Map.of("reason", "EXPIRED")));

        assertThat(onlyRow().getStatus()).isEqualTo(KycChainPropagation.Status.SUPERSEDED);
        verifyNoInteractions(registrationService);
        verify(erc3643Api, never()).revokeComplianceClaims(any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("a failing claim step keeps the suspension, marks the row FAILED and raises the alert gauge")
    void claimFailure_marksFailedButKeepsSuspension() {
        when(erc3643Api.revokeComplianceClaims(any(), any(), any(), any(), anyMap()))
                .thenThrow(new IllegalStateException("rpc down"));

        listener.on(new KycExpiringEvent(entityId, null, Map.of("reason", "EXPIRED")));

        verify(registrationService).suspend(eq(registrationId), anyString(), eq(null), eq("SYSTEM"));
        KycChainPropagation row = onlyRow();
        assertThat(row.getStatus()).isEqualTo(KycChainPropagation.Status.FAILED);
        assertThat(row.getLastError()).contains("rpc down");
        assertThat(meterRegistry.get("registerwerk_kyc_chain_propagation_failed").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an in-flight reinstatement is waited out, then suspended on the next pass")
    void reinstatePending_waitsThenSuspends() {
        registration.setStatus(OrgRegistrationStatus.REINSTATE_PENDING);
        when(erc3643Api.revokeComplianceClaims(any(), any(), any(), any(), anyMap())).thenReturn(0);

        listener.on(new KycExpiringEvent(entityId, null, Map.of("reason", "EXPIRED")));
        assertThat(onlyRow().getOrgAction()).isEqualTo(KycChainPropagationListener.ORG_WAITING);
        verify(registrationService, never()).suspend(any(), any(), any(), any());

        registration.setStatus(OrgRegistrationStatus.ACTIVE);
        listener.retryOpen();
        verify(registrationService).suspend(eq(registrationId), anyString(), eq(null), eq("SYSTEM"));
    }
}
