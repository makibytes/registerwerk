package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionAnnouncedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionCancelledEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposalApprovedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposalRejectedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
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

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the issuer-proposal lifecycle (propose → approve/reject, or withdraw) added so DIVIDEND/
 * SPLIT/CALL finally have a creation path — previously only COUPON/REDEMPTION could ever be
 * created, and only by two cron jobs. Also covers the D1 fix: a PROPOSED row must be structurally
 * invisible to the settlement pipeline, not just filtered by convention.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionService proposal lifecycle unit tests")
class CorporateActionProposalFlowTest {

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

    private CorporateActionProposalFlowTest init() {
        service = new CorporateActionService(repository, entryRepository, holderRepository, settlementWriter,
                couponPaymentRepository, proposalValidator, events, holderBlockGate, finalityGate);
        return this;
    }

    private static CorporateAction actionWithId(UUID id, UUID assetId, CorporateAction.Status status) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", id);
        ca.setAssetId(assetId);
        ca.setActionType(CorporateAction.ActionType.DIVIDEND);
        ca.setStatus(status);
        return ca;
    }

    @Test
    @DisplayName("propose validates via CorporateActionProposalValidator, sets initiatedBy/PROPOSED, and publishes CorporateActionProposedEvent")
    void propose_buildsAndSavesProposedAction() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ProposeCorporateActionRequest request = mock(ProposeCorporateActionRequest.class);
        CorporateAction built = new CorporateAction();
        built.setAssetId(assetId);
        built.setActionType(CorporateAction.ActionType.DIVIDEND);

        when(proposalValidator.validateAndBuild(assetId, request)).thenReturn(built);
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.propose(assetId, request, actorId, "ISSUER");

        assertThat(result.getStatus()).isEqualTo(CorporateAction.Status.PROPOSED);
        assertThat(result.getInitiatedBy()).isEqualTo(actorId);

        ArgumentCaptor<CorporateActionProposedEvent> captor = ArgumentCaptor.forClass(CorporateActionProposedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().actorRole()).isEqualTo("ISSUER");
        assertThat(captor.getValue().assetId()).isEqualTo(assetId);
    }

    @Test
    @DisplayName("approveProposal transitions PROPOSED -> ANNOUNCED and publishes both the review event and CorporateActionAnnouncedEvent")
    void approveProposal_transitionsToAnnounced() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID proposerId = UUID.randomUUID();
        CorporateAction proposed = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.PROPOSED);
        proposed.setInitiatedBy(proposerId);
        when(repository.findById(actionId)).thenReturn(Optional.of(proposed));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.approveProposal(actionId, actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(CorporateAction.Status.ANNOUNCED);

        ArgumentCaptor<CorporateActionProposalApprovedEvent> approvedCaptor =
                ArgumentCaptor.forClass(CorporateActionProposalApprovedEvent.class);
        verify(events).publishEvent(approvedCaptor.capture());
        assertThat(approvedCaptor.getValue().proposedBy()).isEqualTo(proposerId);

        ArgumentCaptor<CorporateActionAnnouncedEvent> announcedCaptor =
                ArgumentCaptor.forClass(CorporateActionAnnouncedEvent.class);
        verify(events).publishEvent(announcedCaptor.capture());
        assertThat(announcedCaptor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("approveProposal refuses an action that is not PROPOSED")
    void approveProposal_refusesWrongStatus() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction announced = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.ANNOUNCED);
        when(repository.findById(actionId)).thenReturn(Optional.of(announced));

        assertThatThrownBy(() -> service.approveProposal(actionId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class);
        verify(events, never()).publishEvent(any(CorporateActionAnnouncedEvent.class));
    }

    @Test
    @DisplayName("rejectProposal transitions PROPOSED -> REJECTED, terminal and distinct from CANCELLED")
    void rejectProposal_transitionsToRejected() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CorporateAction proposed = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.PROPOSED);
        when(repository.findById(actionId)).thenReturn(Optional.of(proposed));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.rejectProposal(actionId, "not economically viable", actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(CorporateAction.Status.REJECTED);
        assertThat(result.getNotes()).contains("rejected: not economically viable");

        ArgumentCaptor<CorporateActionProposalRejectedEvent> captor =
                ArgumentCaptor.forClass(CorporateActionProposalRejectedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("not economically viable");
    }

    @Test
    @DisplayName("withdrawProposal lets the issuer cancel their own still-PROPOSED action")
    void withdrawProposal_cancelsOwnProposal() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CorporateAction proposed = actionWithId(actionId, assetId, CorporateAction.Status.PROPOSED);
        when(repository.findById(actionId)).thenReturn(Optional.of(proposed));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.withdrawProposal(assetId, actionId, actorId);

        assertThat(result.getStatus()).isEqualTo(CorporateAction.Status.CANCELLED);

        ArgumentCaptor<CorporateActionCancelledEvent> captor = ArgumentCaptor.forClass(CorporateActionCancelledEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("withdrawProposal refuses when the corporateActionId belongs to a different asset than the path's assetId")
    void withdrawProposal_refusesCrossAssetMismatch() {
        init();
        UUID actualAssetId = UUID.randomUUID();
        UUID differentAssetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        CorporateAction proposed = actionWithId(actionId, actualAssetId, CorporateAction.Status.PROPOSED);
        when(repository.findById(actionId)).thenReturn(Optional.of(proposed));

        assertThatThrownBy(() -> service.withdrawProposal(differentAssetId, actionId, UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("D1 regression: findDueForSettlement's NOT IN clause must exclude PROPOSED and REJECTED — "
            + "processDailyTransitions has no client-side filter of its own and fully trusts this query's "
            + "contract, so an issuer's still-unreviewed (or rejected) draft must never be handed back here "
            + "just because a client-supplied paymentDate happens to be in the past")
    void findDueForSettlement_queryExcludesProposedAndRejected() throws NoSuchMethodException {
        String jpql = CorporateActionRepository.class
                .getMethod("findDueForSettlement", LocalDate.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class)
                .value();

        assertThat(jpql).contains("'PROPOSED'");
        assertThat(jpql).contains("'REJECTED'");
        assertThat(jpql).contains("NOT IN");
    }
}
