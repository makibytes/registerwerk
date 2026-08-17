package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionAnnouncedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionCancelledEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the corporate-action entry snapshot/compute/close logic added to fix two dead-end
 * lifecycle states (COMPUTED and CLOSED were declared but never reached by any code) and to
 * populate the per-holder CorporateActionEntry breakdown that Steuerbescheinigung/corporate-action
 * confirmations need — previously every coupon/dividend action only carried an asset-level total
 * with no per-holder line items to compute real income from.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionService entry snapshot / compute / close unit tests")
class CorporateActionServiceTest {

    @Mock private CorporateActionRepository repository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private CorporateActionSettlementWriter settlementWriter;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private ApplicationEventPublisher events;
    @Mock private HolderBlockGate holderBlockGate;

    private CorporateActionService service;

    private CorporateActionServiceTest init() {
        service = new CorporateActionService(repository, entryRepository, holderRepository, settlementWriter,
                couponPaymentRepository, events, holderBlockGate);
        return this;
    }

    private static CorporateAction actionWithId(UUID id, CorporateAction.Status status) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", id);
        ca.setAssetId(UUID.randomUUID());
        ca.setStatus(status);
        return ca;
    }

    private static AssetHolder holder(UUID investorId, BigDecimal nominal) {
        AssetHolder h = new AssetHolder();
        ReflectionTestUtils.setField(h, "id", UUID.randomUUID());
        h.setInvestorId(investorId);
        h.setNominalAmount(nominal);
        h.setWalletAddress("0xabc");
        return h;
    }

    @Test
    @DisplayName("record-date reached: snapshots one entry per holder and computes totalAmount from amountPerUnit")
    void processDailyTransitions_snapshotsEntriesAndComputes() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction ca = actionWithId(actionId, CorporateAction.Status.ANNOUNCED);
        ca.setAmountPerUnit(new BigDecimal("0.045")); // 4.5% coupon per unit of nominal
        ca.setRecordDate(LocalDate.now());
        ca.setPaymentDate(LocalDate.now().plusDays(5));

        UUID investorA = UUID.randomUUID();
        UUID investorB = UUID.randomUUID();
        List<AssetHolder> holders = List.of(
                holder(investorA, new BigDecimal("1000")),
                holder(investorB, new BigDecimal("2000")));

        when(repository.findReadyToCompute(any())).thenReturn(List.of(ca));
        when(repository.findDueForSettlement(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(entryRepository.existsByCorporateActionId(actionId)).thenReturn(false);
        when(holderRepository.findActiveByAssetId(ca.getAssetId())).thenReturn(holders);

        service.processDailyTransitions();

        ArgumentCaptor<CorporateActionEntry> entryCaptor = ArgumentCaptor.forClass(CorporateActionEntry.class);
        verify(entryRepository, org.mockito.Mockito.times(2)).save(entryCaptor.capture());
        List<CorporateActionEntry> savedEntries = entryCaptor.getAllValues();
        assertThat(savedEntries).extracting(CorporateActionEntry::getInvestorId)
                .containsExactlyInAnyOrder(investorA, investorB);
        assertThat(savedEntries).extracting(CorporateActionEntry::getEntitlementAmount)
                .containsExactlyInAnyOrder(new BigDecimal("45.000"), new BigDecimal("90.000"));

        // Second save() call is the COMPUTED transition with totalAmount set.
        ArgumentCaptor<CorporateAction> actionCaptor = ArgumentCaptor.forClass(CorporateAction.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(actionCaptor.capture());
        CorporateAction finalState = actionCaptor.getAllValues().get(actionCaptor.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(finalState.getTotalAmount()).isEqualByComparingTo("135.000");
    }

    @Test
    @DisplayName("action without amountPerUnit still reaches COMPUTED (nothing to compute, but not a dead end)")
    void processDailyTransitions_computesEvenWithoutAmountPerUnit() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction ca = actionWithId(actionId, CorporateAction.Status.ANNOUNCED);
        ca.setActionType(CorporateAction.ActionType.SPLIT);
        ca.setRecordDate(LocalDate.now());

        when(repository.findReadyToCompute(any())).thenReturn(List.of(ca));
        when(repository.findDueForSettlement(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(entryRepository.existsByCorporateActionId(actionId)).thenReturn(false);
        when(holderRepository.findActiveByAssetId(ca.getAssetId())).thenReturn(List.of());

        service.processDailyTransitions();

        ArgumentCaptor<CorporateAction> actionCaptor = ArgumentCaptor.forClass(CorporateAction.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(actionCaptor.capture());
        CorporateAction finalState = actionCaptor.getAllValues().get(actionCaptor.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
    }

    @Test
    @DisplayName("SETTLED actions are closed — the previously-unreachable terminal transition")
    void processDailyTransitions_closesSettledActions() {
        init();
        CorporateAction settled = actionWithId(UUID.randomUUID(), CorporateAction.Status.SETTLED);

        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findDueForSettlement(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of(settled));

        service.processDailyTransitions();

        assertThat(settled.getStatus()).isEqualTo(CorporateAction.Status.CLOSED);
        verify(repository).save(settled);
    }

    @Test
    @DisplayName("markSettledManually delegates to the settlement writer only when AWAITING_SETTLEMENT")
    void markSettledManually_requiresAwaitingSettlement() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction awaiting = actionWithId(actionId, CorporateAction.Status.AWAITING_SETTLEMENT);
        CorporateAction afterSettle = actionWithId(actionId, CorporateAction.Status.SETTLED);

        when(repository.findById(actionId)).thenReturn(Optional.of(awaiting), Optional.of(afterSettle));

        UUID actorId = UUID.randomUUID();
        CorporateAction result = service.markSettledManually(actionId, "manual-ref-123", actorId, "REGISTRY_ADMIN");

        verify(settlementWriter).markSettled(actionId, "manual-ref-123", actorId, "REGISTRY_ADMIN");
        assertThat(result.getStatus()).isEqualTo(CorporateAction.Status.SETTLED);
    }

    @Test
    @DisplayName("markSettledManually rejects an action that isn't AWAITING_SETTLEMENT")
    void markSettledManually_rejectsWrongStatus() {
        init();
        UUID actionId = UUID.randomUUID();
        CorporateAction closed = actionWithId(actionId, CorporateAction.Status.CLOSED);
        when(repository.findById(actionId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.markSettledManually(actionId, "ref", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class);
        verify(settlementWriter, never()).markSettled(any(), any(), any(), any());
    }

    private static CorporateActionEntry entryFor(UUID corporateActionId, UUID investorId, String wallet) {
        CorporateActionEntry entry = new CorporateActionEntry();
        entry.setCorporateActionId(corporateActionId);
        entry.setInvestorId(investorId);
        entry.setWalletAddress(wallet);
        entry.setNominalAtRecord(BigDecimal.TEN);
        return entry;
    }

    @Test
    @DisplayName("markSettledManually refuses when any entitled holder has an active Sperrvermerk")
    void markSettledManually_refusesBlockedEntitledHolder() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID cleanInvestor = UUID.randomUUID();
        UUID blockedInvestor = UUID.randomUUID();
        CorporateAction awaiting = actionWithId(actionId, CorporateAction.Status.AWAITING_SETTLEMENT);
        when(repository.findById(actionId)).thenReturn(Optional.of(awaiting));
        when(entryRepository.findByCorporateActionId(actionId)).thenReturn(List.of(
                entryFor(actionId, cleanInvestor, "0x" + "11".repeat(20)),
                entryFor(actionId, blockedInvestor, "0x" + "22".repeat(20))));
        when(holderBlockGate.isBlocked(cleanInvestor, "0x" + "11".repeat(20))).thenReturn(false);
        when(holderBlockGate.isBlocked(blockedInvestor, "0x" + "22".repeat(20))).thenReturn(true);

        assertThatThrownBy(() -> service.markSettledManually(actionId, "ref", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("Sperrvermerk");
        verify(settlementWriter, never()).markSettled(any(), any(), any(), any());
    }

    @Test
    @DisplayName("processDailyTransitions holds settlement (leaves COMPUTED) when an entitled holder is blocked")
    void processDailyTransitions_holdsSettlementForBlockedEntitledHolder() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID blockedInvestor = UUID.randomUUID();
        CorporateAction due = actionWithId(actionId, CorporateAction.Status.COMPUTED);
        due.setDualControlApproverId(UUID.randomUUID());
        due.setPaymentDate(LocalDate.now());

        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findDueForSettlement(any())).thenReturn(List.of(due));
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(entryRepository.findByCorporateActionId(actionId)).thenReturn(
                List.of(entryFor(actionId, blockedInvestor, "0x" + "33".repeat(20))));
        when(holderBlockGate.isBlocked(blockedInvestor, "0x" + "33".repeat(20))).thenReturn(true);

        service.processDailyTransitions();

        assertThat(due.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        verify(events, never()).publishEvent(any(de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent.class));
    }

    @Test
    @DisplayName("announce publishes a system-attributed audit event")
    void announce_publishesAuditEvent() {
        init();
        UUID assetId = UUID.randomUUID();
        CorporateAction action = new CorporateAction();
        action.setAssetId(assetId);
        action.setActionType(CorporateAction.ActionType.REDEMPTION);
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.announce(action);

        ArgumentCaptor<CorporateActionAnnouncedEvent> captor = ArgumentCaptor.forClass(CorporateActionAnnouncedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isNull();
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().assetId()).isEqualTo(assetId);
        assertThat(captor.getValue().actionType()).isEqualTo(CorporateAction.ActionType.REDEMPTION);
    }

    @Test
    @DisplayName("cancel transitions an ANNOUNCED action to CANCELLED and publishes an audit event")
    void cancel_cancelsAnnouncedAction() {
        init();
        UUID actionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CorporateAction announced = actionWithId(actionId, CorporateAction.Status.ANNOUNCED);
        when(repository.findById(actionId)).thenReturn(Optional.of(announced));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));

        CorporateAction result = service.cancel(actionId, "raised against the wrong asset", actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(CorporateAction.Status.CANCELLED);
        assertThat(result.getNotes()).contains("cancelled: raised against the wrong asset");

        ArgumentCaptor<CorporateActionCancelledEvent> captor = ArgumentCaptor.forClass(CorporateActionCancelledEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().corporateActionId()).isEqualTo(actionId);
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().reason()).isEqualTo("raised against the wrong asset");
    }

    @Test
    @DisplayName("cancel refuses an action that is already AWAITING_SETTLEMENT, SETTLED, CLOSED or CANCELLED")
    void cancel_refusesTerminalOrInFlightStatuses() {
        init();
        for (CorporateAction.Status status : List.of(CorporateAction.Status.AWAITING_SETTLEMENT,
                CorporateAction.Status.SETTLED, CorporateAction.Status.CLOSED, CorporateAction.Status.CANCELLED)) {
            UUID actionId = UUID.randomUUID();
            CorporateAction ca = actionWithId(actionId, status);
            when(repository.findById(actionId)).thenReturn(Optional.of(ca));

            assertThatThrownBy(() -> service.cancel(actionId, "reason", UUID.randomUUID(), "REGISTRY_ADMIN"))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(events, never()).publishEvent(any(CorporateActionCancelledEvent.class));
    }
}
