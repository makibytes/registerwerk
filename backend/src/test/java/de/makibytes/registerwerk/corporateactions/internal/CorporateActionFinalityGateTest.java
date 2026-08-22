package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityNotReachedException;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the three real {@link GatedOperation#CORPORATE_ACTION_SETTLEMENT_CONFIRM} call sites
 * that were declared but unwired before this plan (D5): the controller-driven
 * {@code confirmSettlementAsOperator}/{@code markSettledManually} paths use
 * {@link FinalityGate#require}, throwing straight through as a 409; the cron path ({@code settle},
 * exercised via {@code processDailyTransitions}) uses {@link FinalityGate#check} and must
 * skip-and-reattempt on {@link FinalityDecision.Blocked} rather than let an uncaught exception
 * fail the whole daily run.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionService FinalityGate wiring unit tests")
class CorporateActionFinalityGateTest {

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

    private CorporateActionFinalityGateTest init() {
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
    @DisplayName("confirmSettlementAsOperator gates on CORPORATE_ACTION_SETTLEMENT_CONFIRM with currentLevel=FINALIZED, "
            + "and a Blocked-turned-exception from require() propagates (the controller write never happens)")
    void confirmSettlementAsOperator_gatesAndPropagatesBlockedException() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        CorporateAction attested = actionWithId(actionId, assetId, CorporateAction.Status.COMPUTED);
        attested.setIssuerAttestedBy(UUID.randomUUID());
        attested.setIssuerAttestedAt(java.time.Instant.now());
        when(repository.findById(actionId)).thenReturn(Optional.of(attested));
        when(repository.findTokenStandardByCorpAction(actionId)).thenReturn("ERC3643");

        FinalityDecision.Blocked blocked = new FinalityDecision.Blocked(
                GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM, assetId, FinalityLevel.FINALIZED,
                FinalityLevel.PROVISIONAL, FinalityDecision.Blocked.Reason.BELOW_REQUIRED, "not yet final");
        org.mockito.Mockito.doThrow(new FinalityNotReachedException(blocked))
                .when(finalityGate).require(eq(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM),
                        eq(assetId), eq(TokenStandard.ERC3643), eq(FinalityLevel.FINALIZED));

        assertThatThrownBy(() -> service.confirmSettlementAsOperator(actionId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(FinalityNotReachedException.class);

        // The gate check happens before the write — a blocked decision must never let the
        // operator-confirmation fields get persisted.
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("markSettledManually gates on CORPORATE_ACTION_SETTLEMENT_CONFIRM before delegating to the settlement writer")
    void markSettledManually_gatesBeforeWrite() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction awaiting = actionWithId(actionId, UUID.randomUUID(), CorporateAction.Status.AWAITING_SETTLEMENT);
        when(repository.findById(actionId)).thenReturn(Optional.of(awaiting));
        when(repository.findTokenStandardByCorpAction(actionId)).thenReturn(null);

        service.markSettledManually(actionId, "manual-ref", UUID.randomUUID(), "REGISTRY_ADMIN");

        verify(finalityGate).require(eq(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM),
                eq(awaiting.getAssetId()), eq((TokenStandard) null), eq(FinalityLevel.FINALIZED));
    }

    @Test
    @DisplayName("cron settle path: check() returning Blocked holds the action (stays COMPUTED, no dispatch event) instead of throwing")
    void cronSettle_skipsAndReattemptsOnBlocked() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        CorporateAction due = actionWithId(actionId, assetId, CorporateAction.Status.COMPUTED);
        due.setIssuerAttestedBy(UUID.randomUUID());
        due.setIssuerAttestedAt(java.time.Instant.now());
        due.setDualControlApproverId(UUID.randomUUID());
        due.setDualControlApprovedAt(java.time.Instant.now());
        due.setPaymentDate(LocalDate.now());

        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findDueForSettlement(any())).thenReturn(List.of(due));
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(repository.findTokenStandardByCorpAction(actionId)).thenReturn(null);

        FinalityDecision.Blocked blocked = new FinalityDecision.Blocked(
                GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM, assetId, FinalityLevel.FINALIZED,
                FinalityLevel.PROVISIONAL, FinalityDecision.Blocked.Reason.BELOW_REQUIRED, "not yet final");
        when(finalityGate.check(eq(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM),
                eq(assetId), any(), eq(FinalityLevel.FINALIZED))).thenReturn(blocked);

        service.processDailyTransitions();

        assertThat(due.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        verify(events, never()).publishEvent(any(CorporateActionSettlementRequestedEvent.class));
        // Never even reaches the entitled-holder block check — held at the finality gate first.
        verify(entryRepository, never()).findByCorporateActionId(any());
    }

    @Test
    @DisplayName("cron settle path: an Allowed decision proceeds to dispatch settlement as before")
    void cronSettle_proceedsOnAllowed() {
        init();
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        CorporateAction due = actionWithId(actionId, assetId, CorporateAction.Status.COMPUTED);
        due.setIssuerAttestedBy(UUID.randomUUID());
        due.setIssuerAttestedAt(java.time.Instant.now());
        due.setDualControlApproverId(UUID.randomUUID());
        due.setDualControlApprovedAt(java.time.Instant.now());
        due.setPaymentDate(LocalDate.now());

        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findDueForSettlement(any())).thenReturn(List.of(due));
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(repository.findTokenStandardByCorpAction(actionId)).thenReturn(null);
        when(entryRepository.findByCorporateActionId(actionId)).thenReturn(List.of());
        when(finalityGate.check(eq(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM),
                eq(assetId), any(), eq(FinalityLevel.FINALIZED))).thenReturn(new FinalityDecision.Allowed(FinalityLevel.FINALIZED));

        service.processDailyTransitions();

        assertThat(due.getStatus()).isEqualTo(CorporateAction.Status.AWAITING_SETTLEMENT);
        verify(events, times(1)).publishEvent(any(CorporateActionSettlementRequestedEvent.class));
    }
}
