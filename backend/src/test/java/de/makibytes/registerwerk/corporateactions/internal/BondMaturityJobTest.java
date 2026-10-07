package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-05. Bond under test matures Mon 30 Jun 2025 (TARGET2 business day): payment 30 Jun, record
 * Fri 27 Jun (offset 1), announcement Fri 20 Jun (lead 5 business days).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BondMaturityJob unit tests")
class BondMaturityJobTest {

    private static final LocalDate MATURITY = LocalDate.of(2025, 6, 30);

    @Mock private AssetBondTermsRepository bondTermsRepository;
    @Mock private CorporateActionRepository corporateActionRepository;
    @Mock private CorporateActionService corporateActionService;
    @Mock private AssetRepository assetRepository;
    @Mock private de.makibytes.registerwerk.customer.api.EntityTaskPort entityTasks;
    @Mock private org.springframework.context.ApplicationEventPublisher events;

    private BondMaturityJob jobOn(LocalDate today) {
        return new BondMaturityJob(bondTermsRepository, corporateActionRepository, corporateActionService,
                assetRepository, CorporateActionTestSupport.registerClockAt(today),
                CorporateActionTestSupport.directTransactions(), entityTasks, events);
    }

    private AssetBondTerms terms(BondStatus status) {
        AssetBondTerms t = new AssetBondTerms();
        t.setAssetId(UUID.randomUUID());
        t.setBondStatus(status);
        t.setMaturityDate(MATURITY);
        t.setFaceValue(new BigDecimal("1000"));
        t.setCurrencyIso("EUR");
        org.mockito.Mockito.lenient().when(bondTermsRepository.findByBondStatus(status)).thenReturn(List.of(t));
        when(bondTermsRepository.findById(t.getAssetId())).thenReturn(Optional.of(t));
        return t;
    }

    private static CorporateAction unsettledRedemption(LocalDate paymentDate) {
        CorporateAction ca = new CorporateAction();
        ca.setPaymentDate(paymentDate);
        return ca;
    }

    @Test
    @DisplayName("redemption is raised at its announcement date with record/payment dates from the conventions")
    void raisesAtAnnouncementDate() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);

        jobOn(LocalDate.of(2025, 6, 19)).processMaturitiesAndDefaults();
        verify(corporateActionService, never()).announce(any());

        jobOn(LocalDate.of(2025, 6, 20)).processMaturitiesAndDefaults();
        ArgumentCaptor<CorporateAction> captor = ArgumentCaptor.forClass(CorporateAction.class);
        verify(corporateActionService).announce(captor.capture());
        CorporateAction ca = captor.getValue();
        assertThat(ca.getActionType()).isEqualTo(CorporateAction.ActionType.REDEMPTION);
        assertThat(ca.getAnnouncementDate()).isEqualTo(LocalDate.of(2025, 6, 20));
        assertThat(ca.getRecordDate()).isEqualTo(LocalDate.of(2025, 6, 27));
        assertThat(ca.getPaymentDate()).isEqualTo(MATURITY);
        assertThat(ca.getAmountPerUnit()).isEqualByComparingTo("1000");
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.ACTIVE);
    }

    @Test
    @DisplayName("a bond past its maturity date transitions to MATURED")
    void maturityDateTransitionsToMatured() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t));

        jobOn(MATURITY).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
    }

    @Test
    @DisplayName("a redemption settled on the payment date is never flagged OVERDUE/DEFAULTED")
    void settledOnPaymentDateIsNeverDefaulted() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        // Settled → no row from findOverdueRedemptions.
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of());

        jobOn(MATURITY.plusDays(1)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("an unsettled redemption is OVERDUE inside the principal grace period and DEFAULTED only after it")
    void defaultedOnlyAfterGrace() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        when(corporateActionRepository.findOverdueRedemptions(any(), any()))
                .thenReturn(List.of(unsettledRedemption(MATURITY)));

        jobOn(MATURITY.plusDays(1)).processMaturitiesAndDefaults();
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.OVERDUE);

        // Default grace is 7 days: still OVERDUE on day 7, DEFAULTED on day 8.
        jobOn(MATURITY.plusDays(7)).processMaturitiesAndDefaults();
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.OVERDUE);

        // lenient doReturn: re-stubs a method the job also calls with other statuses (strict stubs flag the mismatch)
        org.mockito.Mockito.lenient().doReturn(List.of(t)).when(bondTermsRepository).findByBondStatus(BondStatus.OVERDUE);
        jobOn(MATURITY.plusDays(8)).processMaturitiesAndDefaults();
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.DEFAULTED);
    }

    @Test
    @DisplayName("a bond whose CALL settled is CALLED at maturity and gets no second redemption")
    void callSettledSetsCalledAndSkipsMaturityRedemption() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t));
        when(corporateActionRepository.existsSettledCallForAsset(t.getAssetId())).thenReturn(true);

        jobOn(MATURITY).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.CALLED);
        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("a called bond that is not yet past maturity gets no redemption at its announcement date either")
    void calledBondSkipsRedemptionBeforeMaturity() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(corporateActionRepository.existsSettledCallForAsset(t.getAssetId())).thenReturn(true);

        jobOn(LocalDate.of(2025, 6, 24)).processMaturitiesAndDefaults();

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("a register transferred to a successor operator raises no redemption")
    void transferredOutRaisesNothing() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        Asset transferred = new Asset();
        transferred.setStatus(AssetStatus.TRANSFERRED_OUT);
        when(assetRepository.findById(t.getAssetId())).thenReturn(Optional.of(transferred));

        jobOn(LocalDate.of(2025, 6, 24)).processMaturitiesAndDefaults();

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("does not raise a second redemption action if one is already active")
    void doesNotDuplicateRedemption() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);

        jobOn(LocalDate.of(2025, 6, 24)).processMaturitiesAndDefaults();

        verify(corporateActionService, never()).announce(any());
    }

    // ── Wave 0b H6: registry-side blocks are never escalated ───────────────────

    private CorporateAction blockedRedemption(CorporateAction.Status status) {
        CorporateAction ca = unsettledRedemption(MATURITY);
        org.springframework.test.util.ReflectionTestUtils.setField(ca, "id", UUID.randomUUID());
        ca.setStatus(status);
        return ca;
    }

    @Test
    @DisplayName("H6: a redemption parked as SNAPSHOT_BLOCKED stays blocked - not OVERDUE, not DEFAULTED, even long past the grace period")
    void snapshotBlockedRedemptionIsNeverEscalated() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        CorporateAction blocked = blockedRedemption(CorporateAction.Status.SNAPSHOT_BLOCKED);
        blocked.setSnapshotBlockedReason("holder sync BLOCKED, unmapped wallets: [0xabc]");
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(blocked));
        Asset asset = new Asset();
        asset.setIssuerId(UUID.randomUUID());
        asset.setStatus(AssetStatus.ISSUED);
        when(assetRepository.findById(t.getAssetId())).thenReturn(Optional.of(asset));
        when(entityTasks.open(any(), any(), any(), any(), any())).thenReturn(true);

        jobOn(MATURITY.plusDays(90)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
        verify(bondTermsRepository, never()).save(t);
        verify(entityTasks).open(org.mockito.ArgumentMatchers.eq(asset.getIssuerId()),
                org.mockito.ArgumentMatchers.eq(CorporateActionBlocks.TASK_REDEMPTION_BLOCKED),
                org.mockito.ArgumentMatchers.eq(blocked.getId().toString()), any(), any());
        ArgumentCaptor<de.makibytes.registerwerk.corporateactions.api.BondRedemptionBlockedEvent> event =
                ArgumentCaptor.forClass(de.makibytes.registerwerk.corporateactions.api.BondRedemptionBlockedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().cause()).contains("snapshot is blocked");
    }

    @Test
    @DisplayName("H6: a redemption the system holds back (Sperrvermerk / finality hold) is not escalated either")
    void settlementHeldRedemptionIsNeverEscalated() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        CorporateAction held = blockedRedemption(CorporateAction.Status.COMPUTED);
        held.setSettlementHoldReason("an on-ledger call cannot exclude 1 ineligible holder");
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(held));

        jobOn(MATURITY.plusDays(90)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
    }

    @Test
    @DisplayName("H6: a frozen / handed-over register is a registry-side block - the bond is not defaulted for it")
    void frozenRegisterRedemptionIsNeverEscalated() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        CorporateAction pending = blockedRedemption(CorporateAction.Status.COMPUTED);
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(pending));
        Asset frozen = new Asset();
        frozen.setStatus(AssetStatus.TRANSFER_PENDING);
        when(assetRepository.findById(t.getAssetId())).thenReturn(Optional.of(frozen));

        jobOn(MATURITY.plusDays(90)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
    }

    @Test
    @DisplayName("H6: a redemption that only waits for the issuer / operator sign-off still escalates as before")
    void issuerSideRedemptionIsStillEscalated() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        CorporateAction waiting = blockedRedemption(CorporateAction.Status.COMPUTED);
        CorporateAction blocked = blockedRedemption(CorporateAction.Status.SNAPSHOT_BLOCKED);
        blocked.setSnapshotBlockedReason("x");
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(waiting, blocked));

        jobOn(MATURITY.plusDays(90)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.DEFAULTED);
    }

    // ── Wave 2b 9A-04R: operator-side waits never count; transitions are audited and notified ──────────

    private java.util.List<de.makibytes.registerwerk.audit.api.AuditableEvent> publishedAuditable() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(de.makibytes.registerwerk.audit.api.AuditableEvent.class::isInstance)
                .map(de.makibytes.registerwerk.audit.api.AuditableEvent.class::cast).toList();
    }

    private CorporateAction operatorWaitingRedemption(CorporateAction.Status status, boolean attested) {
        CorporateAction ca = blockedRedemption(status);
        if (attested) {
            ca.setIssuerAttestedAt(java.time.Instant.parse("2025-06-25T10:00:00Z"));
        }
        return ca;
    }

    @Test
    @DisplayName("9A-04R: a redemption the OPERATOR has not confirmed yet never makes the bond DEFAULTED")
    void operatorSideWaitIsNeverEscalated() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        CorporateAction waiting = operatorWaitingRedemption(CorporateAction.Status.COMPUTED, true);
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(waiting));
        Asset asset = new Asset();
        asset.setIssuerId(UUID.randomUUID());
        asset.setStatus(AssetStatus.ISSUED);
        when(assetRepository.findById(t.getAssetId())).thenReturn(Optional.of(asset));
        when(entityTasks.open(any(), any(), any(), any(), any())).thenReturn(true);

        jobOn(MATURITY.plusDays(90)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
        verify(entityTasks).open(org.mockito.ArgumentMatchers.eq(asset.getIssuerId()),
                org.mockito.ArgumentMatchers.eq("BOND_REDEMPTION_OPERATOR_PENDING"),
                org.mockito.ArgumentMatchers.eq(waiting.getId().toString()), any(), any());
        assertThat(publishedAuditable()).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo("BOND_REDEMPTION_BLOCKED");
            assertThat(String.valueOf(e.payload().get("cause"))).contains("OPERATOR_CONFIRMATION_MISSING");
        });
    }

    @Test
    @DisplayName("9A-04R: a redemption sitting in AWAITING_SETTLEMENT (manual-settle lag) is operator-side, never DEFAULTED")
    void awaitingSettlementIsNeverEscalated() {
        AssetBondTerms t = terms(BondStatus.OVERDUE);
        CorporateAction waiting = operatorWaitingRedemption(CorporateAction.Status.AWAITING_SETTLEMENT, true);
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(waiting));

        jobOn(MATURITY.plusDays(90)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.OVERDUE);
        assertThat(publishedAuditable()).extracting(e -> e.eventType()).doesNotContain("BOND_DEFAULTED");
        assertThat(publishedAuditable()).anySatisfy(e ->
                assertThat(String.valueOf(e.payload().get("cause"))).contains("SETTLEMENT_NOT_DISPATCHED"));
    }

    @Test
    @DisplayName("9A-04R: an issuer that has not attested still escalates (the issuer side is unchanged)")
    void issuerNotAttestedStillEscalates() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        when(corporateActionRepository.findOverdueRedemptions(any(), any()))
                .thenReturn(List.of(operatorWaitingRedemption(CorporateAction.Status.COMPUTED, false)));

        jobOn(MATURITY.plusDays(90)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.DEFAULTED);
    }

    @Test
    @DisplayName("9A-04R: MATURED, OVERDUE and DEFAULTED each publish one audited event and notify the issuer; unchanged status publishes nothing")
    void bondStatusTransitionsPublishAuditEvents() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t)).thenReturn(List.of());
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        CorporateAction unsettled = unsettledRedemption(MATURITY);
        ReflectionTestUtils.setField(unsettled, "id", UUID.randomUUID());
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of());
        Asset asset = new Asset();
        asset.setIssuerId(UUID.randomUUID());
        asset.setStatus(AssetStatus.ISSUED);
        when(assetRepository.findById(t.getAssetId())).thenReturn(Optional.of(asset));
        when(entityTasks.open(any(), any(), any(), any(), any())).thenReturn(true);

        jobOn(MATURITY).processMaturitiesAndDefaults();                 // ACTIVE -> MATURED (not yet overdue)
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(unsettled));
        jobOn(MATURITY.plusDays(1)).processMaturitiesAndDefaults();     // MATURED -> OVERDUE
        jobOn(MATURITY.plusDays(2)).processMaturitiesAndDefaults();     // still OVERDUE: no new event
        org.mockito.Mockito.lenient().when(bondTermsRepository.findByBondStatus(BondStatus.OVERDUE)).thenReturn(List.of(t));
        jobOn(MATURITY.plusDays(8)).processMaturitiesAndDefaults();     // OVERDUE -> DEFAULTED

        assertThat(publishedAuditable()).extracting(e -> e.eventType())
                .containsExactly("BOND_MATURED", "BOND_OVERDUE", "BOND_DEFAULTED");
        assertThat(publishedAuditable().get(2).payload()).containsKeys("from", "to", "actionIds", "graceDays");
        verify(entityTasks).open(org.mockito.ArgumentMatchers.eq(asset.getIssuerId()),
                org.mockito.ArgumentMatchers.eq("BOND_OVERDUE"), any(), any(), any());
        verify(entityTasks).open(org.mockito.ArgumentMatchers.eq(asset.getIssuerId()),
                org.mockito.ArgumentMatchers.eq("BOND_DEFAULT_REVIEW"), any(), any(), any());
    }

    // ── Wave 0b H8: one transaction per bond ───────────────────────────────────

    @Test
    @DisplayName("H8: a bond whose evaluation fails rolls back only itself; the next bond is still transitioned and committed")
    void oneFailingBondDoesNotRollBackTheOthers() {
        org.springframework.transaction.PlatformTransactionManager manager =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        AssetBondTerms broken = new AssetBondTerms();
        broken.setAssetId(UUID.randomUUID());
        broken.setBondStatus(BondStatus.ACTIVE);
        broken.setMaturityDate(MATURITY);
        AssetBondTerms fine = new AssetBondTerms();
        fine.setAssetId(UUID.randomUUID());
        fine.setBondStatus(BondStatus.ACTIVE);
        fine.setMaturityDate(MATURITY);
        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(broken, fine));
        when(bondTermsRepository.findById(broken.getAssetId())).thenReturn(Optional.of(broken));
        when(bondTermsRepository.findById(fine.getAssetId())).thenReturn(Optional.of(fine));
        when(corporateActionRepository.existsSettledCallForAsset(broken.getAssetId()))
                .thenThrow(new IllegalStateException("db hiccup"));
        BondMaturityJob job = new BondMaturityJob(bondTermsRepository, corporateActionRepository, corporateActionService,
                assetRepository, CorporateActionTestSupport.registerClockAt(MATURITY),
                new de.makibytes.registerwerk.shared.IsolatedTransactionExecutor(manager), entityTasks, events);

        job.processMaturitiesAndDefaults();

        assertThat(broken.getBondStatus()).isEqualTo(BondStatus.ACTIVE);
        assertThat(fine.getBondStatus()).isEqualTo(BondStatus.MATURED);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(manager);
        order.verify(manager).getTransaction(any());
        order.verify(manager).rollback(any());
        order.verify(manager).getTransaction(any());
        order.verify(manager).commit(any());
    }
}
