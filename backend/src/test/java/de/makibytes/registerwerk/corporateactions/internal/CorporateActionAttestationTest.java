package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestationOverriddenEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionOperatorConfirmedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two-party settlement-approval control: issuer attestation (or an audited operator
 * override) must precede operator confirmation — replacing the old same-org 2x-operator dual
 * control with a cross-party one. See {@code CorporateActionService}'s class javadoc.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionService issuer-attestation / operator-confirmation unit tests")
class CorporateActionAttestationTest {

    @Mock private CorporateActionRepository repository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private CorporateActionSettlementWriter settlementWriter;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private CorporateActionProposalValidator proposalValidator;
    @Mock private ApplicationEventPublisher events;
    @Mock private HolderBlockGate holderBlockGate;
    @Mock private FinalityGate finalityGate;

    private CorporateActionService service;

    private CorporateActionAttestationTest init() {
        service = new CorporateActionService(repository, entryRepository, holderRepository, settlementWriter,
                couponPaymentRepository, proposalValidator, events, holderBlockGate, finalityGate);
        return this;
    }

    private static CorporateAction actionWithId(UUID id, UUID assetId, CorporateAction.Status status) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", id);
        ca.setAssetId(assetId);
        ca.setStatus(status);
        return ca;
    }

    @Test
    @DisplayName("attestSettlementAsIssuer records the issuer's attestation and publishes CorporateActionIssuerAttestedEvent")
    void attestSettlementAsIssuer_recordsAttestation() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CorporateAction announced = actionWithId(actionId, assetId, CorporateAction.Status.ANNOUNCED);
        when(repository.findById(actionId)).thenReturn(Optional.of(announced));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.attestSettlementAsIssuer(assetId, actionId, "SEPA-REF-123", actorId, "ISSUER");

        assertThat(result.getIssuerAttestedBy()).isEqualTo(actorId);
        assertThat(result.getIssuerAttestedAt()).isNotNull();
        assertThat(result.getIssuerAttestationRef()).isEqualTo("SEPA-REF-123");

        ArgumentCaptor<CorporateActionIssuerAttestedEvent> captor = ArgumentCaptor.forClass(CorporateActionIssuerAttestedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("attestSettlementAsIssuer refuses when corporateActionId belongs to a different asset")
    void attestSettlementAsIssuer_refusesCrossAssetMismatch() {
        init();
        UUID actualAssetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        CorporateAction announced = actionWithId(actionId, actualAssetId, CorporateAction.Status.ANNOUNCED);
        when(repository.findById(actionId)).thenReturn(Optional.of(announced));

        assertThatThrownBy(() -> service.attestSettlementAsIssuer(UUID.randomUUID(), actionId, "ref", UUID.randomUUID(), "ISSUER"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("attestSettlementAsIssuer refuses on a PROPOSED action (must be approved/announced first)")
    void attestSettlementAsIssuer_refusesProposedAction() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        CorporateAction proposed = actionWithId(actionId, assetId, CorporateAction.Status.PROPOSED);
        when(repository.findById(actionId)).thenReturn(Optional.of(proposed));

        assertThatThrownBy(() -> service.attestSettlementAsIssuer(assetId, actionId, "ref", UUID.randomUUID(), "ISSUER"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("attestSettlementAsIssuer refuses on a SETTLED (already-terminal) action")
    void attestSettlementAsIssuer_refusesSettledAction() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        CorporateAction settled = actionWithId(actionId, assetId, CorporateAction.Status.SETTLED);
        when(repository.findById(actionId)).thenReturn(Optional.of(settled));

        assertThatThrownBy(() -> service.attestSettlementAsIssuer(assetId, actionId, "ref", UUID.randomUUID(), "ISSUER"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("overrideIssuerAttestation writes an OPERATOR_OVERRIDE-prefixed ref and publishes a distinct event type")
    void overrideIssuerAttestation_writesDistinctEvent() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        CorporateAction announced = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.ANNOUNCED);
        when(repository.findById(actionId)).thenReturn(Optional.of(announced));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.overrideIssuerAttestation(actionId, "issuer unreachable", operatorId, "REGISTRY_ADMIN");

        assertThat(result.getIssuerAttestedBy()).isEqualTo(operatorId);
        assertThat(result.getIssuerAttestationRef()).isEqualTo("OPERATOR_OVERRIDE: issuer unreachable");

        verify(events).publishEvent(any(CorporateActionIssuerAttestationOverriddenEvent.class));
        verify(events, never()).publishEvent(any(CorporateActionIssuerAttestedEvent.class));
    }

    @Test
    @DisplayName("confirmSettlementAsOperator refuses while the issuer half is missing")
    void confirmSettlementAsOperator_refusesMissingIssuerAttestation() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction unattested = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.COMPUTED);
        when(repository.findById(actionId)).thenReturn(Optional.of(unattested));

        assertThatThrownBy(() -> service.confirmSettlementAsOperator(actionId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been attested by its issuer");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("confirmSettlementAsOperator refuses when the confirmer is the same actor as the attester")
    void confirmSettlementAsOperator_refusesSelfConfirmation() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID sameActor = UUID.randomUUID();
        CorporateAction attested = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.COMPUTED);
        attested.setIssuerAttestedBy(sameActor);
        attested.setIssuerAttestedAt(java.time.Instant.now());
        when(repository.findById(actionId)).thenReturn(Optional.of(attested));

        assertThatThrownBy(() -> service.confirmSettlementAsOperator(actionId, sameActor, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("confirmSettlementAsOperator succeeds once the issuer has attested, and publishes CorporateActionOperatorConfirmedEvent")
    void confirmSettlementAsOperator_succeedsAfterAttestation() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID issuerActor = UUID.randomUUID();
        UUID operatorActor = UUID.randomUUID();
        CorporateAction attested = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.COMPUTED);
        attested.setIssuerAttestedBy(issuerActor);
        attested.setIssuerAttestedAt(java.time.Instant.now());
        when(repository.findById(actionId)).thenReturn(Optional.of(attested));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.confirmSettlementAsOperator(actionId, operatorActor, "REGISTRY_ADMIN");

        assertThat(result.getDualControlApproverId()).isEqualTo(operatorActor);
        assertThat(result.getDualControlApprovedAt()).isNotNull();

        ArgumentCaptor<CorporateActionOperatorConfirmedEvent> captor =
                ArgumentCaptor.forClass(CorporateActionOperatorConfirmedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().issuerAttestedBy()).isEqualTo(issuerActor);
    }

    @Test
    @DisplayName("confirmSettlementAsOperator refuses on an already-SETTLED action")
    void confirmSettlementAsOperator_refusesAlreadySettled() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction settled = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.SETTLED);
        settled.setIssuerAttestedBy(UUID.randomUUID());
        settled.setIssuerAttestedAt(java.time.Instant.now());
        when(repository.findById(actionId)).thenReturn(Optional.of(settled));

        assertThatThrownBy(() -> service.confirmSettlementAsOperator(actionId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("cron skip: processDailyTransitions leaves a due action alone when either attestation half is missing")
    void processDailyTransitions_skipsWhenEitherAttestationHalfMissing() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction missingIssuerHalf = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.COMPUTED);
        missingIssuerHalf.setDualControlApproverId(UUID.randomUUID());
        missingIssuerHalf.setDualControlApprovedAt(java.time.Instant.now());
        missingIssuerHalf.setPaymentDate(java.time.LocalDate.now());
        // issuerAttestedAt intentionally left null

        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findDueForSettlement(any())).thenReturn(List.of(missingIssuerHalf));
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());

        service.processDailyTransitions();

        assertThat(missingIssuerHalf.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        verify(entryRepository, never()).findByCorporateActionId(any());
        verify(finalityGate, never()).check(any(), any(), any(), any());
    }
}
