package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionPayoutHeldEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementBlockedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 0b H6: the payout gate pays ELIGIBLE holders individually. A holder that fails the party-eligibility gate
 * (entity not active, KYC expired, sanctions hit, Sperrvermerk) is held with the reason - audited, an operator task,
 * never paid - instead of the whole action stalling silently; and a registry-side hold is never presented as the
 * issuer's non-payment (coupon OVERDUE / MISSED).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Corporate-action payout: per-holder eligibility, system holds (H6)")
class CorporateActionPayoutHoldTest {

    @Mock private CorporateActionRepository repository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private RecordDatePositionResolver positionResolver;
    @Mock private CorporateActionSettlementWriter settlementWriter;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private CorporateActionProposalValidator proposalValidator;
    @Mock private ApplicationEventPublisher events;
    @Mock private PartyEligibilityGate partyGate;
    @Mock private EntityTaskPort entityTasks;
    @Mock private FinalityGate finalityGate;
    @Mock private RegisterFreshnessGate freshnessGate;
    @Mock private AssetBondTermsRepository bondTermsRepository;

    private CorporateActionService service;
    private final UUID assetId = UUID.randomUUID();
    private final UUID issuer = UUID.randomUUID();
    private final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CorporateActionService(repository, entryRepository, positionResolver, settlementWriter,
                couponPaymentRepository, proposalValidator, events, partyGate, entityTasks, finalityGate, freshnessGate,
                bondTermsRepository, CorporateActionTestSupport.systemRegisterClock(),
                CorporateActionTestSupport.directTransactions());
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(freshnessGate.blockedReason(any(), any())).thenReturn(Optional.empty());
        when(finalityGate.check(any(), any(), any(), any())).thenReturn(new FinalityDecision.Allowed(FinalityLevel.FINALIZED));
        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(repository.findOverdueCoupons(any())).thenReturn(List.of());
    }

    private CorporateAction dueAction(CorporateAction.Status status) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", UUID.randomUUID());
        ca.setAssetId(assetId);
        ca.setActionType(CorporateAction.ActionType.COUPON);
        ca.setCurrency("EUR");
        ca.setAmountPerUnit(new BigDecimal("0.05"));
        ca.setStatus(status);
        ca.setRecordDate(LocalDate.now().minusDays(2));
        ca.setPaymentDate(LocalDate.now());
        when(repository.findById(ca.getId())).thenReturn(Optional.of(ca));
        return ca;
    }

    private CorporateActionEntry entryOf(CorporateAction ca, UUID investor, String wallet, String entitlement) {
        CorporateActionEntry e = CorporateActionTestSupport.entry(ca.getId(), wallet, "1000", entitlement);
        e.setInvestorId(investor);
        return e;
    }

    /** A COMPUTED, fully signed action over these entries, due today. */
    private CorporateAction signedDue(List<CorporateActionEntry> entries) {
        CorporateAction ca = dueAction(CorporateAction.Status.COMPUTED);
        CorporateActionTestSupport.signedOff(ca, entries, issuer, operator);
        when(entryRepository.findByCorporateActionId(ca.getId())).thenReturn(entries);
        when(repository.findDueForSettlement(any())).thenReturn(List.of(ca));
        return ca;
    }

    // ── eligible holders are paid, the ineligible one is held ───────────────────

    @Test
    @DisplayName("one ineligible holder is HELD_BLOCKED with the reason; the settlement still starts for everybody else")
    void ineligibleHolderIsHeldAndTheOthersArePaid() {
        UUID clean = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        CorporateAction placeholder = new CorporateAction();
        ReflectionTestUtils.setField(placeholder, "id", UUID.randomUUID());
        CorporateActionEntry cleanEntry = entryOf(placeholder, clean, "0xaaa", "50.00");
        CorporateActionEntry heldEntry = entryOf(placeholder, suspended, "0xbbb", "100.00");
        List<CorporateActionEntry> entries = List.of(cleanEntry, heldEntry);
        CorporateAction ca = signedDue(entries);
        ReflectionTestUtils.setField(cleanEntry, "corporateActionId", ca.getId());
        ReflectionTestUtils.setField(heldEntry, "corporateActionId", ca.getId());
        when(partyGate.check(clean, "0xaaa")).thenReturn(List.of());
        when(partyGate.check(suspended, "0xbbb")).thenReturn(List.of("is in status SUSPENDED"));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.AWAITING_SETTLEMENT);
        assertThat(cleanEntry.getPayoutStatus()).isEqualTo(CorporateActionEntry.PayoutStatus.PAYABLE);
        assertThat(heldEntry.getPayoutStatus()).isEqualTo(CorporateActionEntry.PayoutStatus.HELD_BLOCKED);
        assertThat(heldEntry.getHeldReason()).contains("SUSPENDED");
        verify(entryRepository).save(heldEntry);
        verify(events).publishEvent(any(CorporateActionSettlementRequestedEvent.class));
        ArgumentCaptor<CorporateActionPayoutHeldEvent> held = ArgumentCaptor.forClass(CorporateActionPayoutHeldEvent.class);
        verify(events).publishEvent(held.capture());
        assertThat(held.getValue().held()).hasSize(1);
        assertThat(held.getValue().held().getFirst()).containsEntry("investorId", suspended.toString())
                .containsEntry("entitlementAmount", "100.00");
        verify(entityTasks).open(eq(suspended), eq(CorporateActionBlocks.TASK_PAYOUT_HELD),
                eq(ca.getId() + ":" + heldEntry.getAssetHolderId()), anyString(), eq(null));
        verify(entityTasks, never()).open(eq(clean), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a holder the gate cannot identify (no investor) is held, never paid")
    void unidentifiedHolderIsHeld() {
        CorporateAction placeholder = new CorporateAction();
        ReflectionTestUtils.setField(placeholder, "id", UUID.randomUUID());
        CorporateActionEntry orphan = entryOf(placeholder, null, "0xccc", "80.00");
        CorporateAction ca = signedDue(List.of(orphan));
        ReflectionTestUtils.setField(orphan, "corporateActionId", ca.getId());
        when(partyGate.check(null, "0xccc")).thenReturn(List.of("is unknown"));

        service.processDailyTransitions();

        assertThat(orphan.getPayoutStatus()).isEqualTo(CorporateActionEntry.PayoutStatus.HELD_BLOCKED);
        verify(entityTasks, never()).open(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an entry without entitlement (nothing to pay) is not run through the gate")
    void zeroEntitlementIsNotGated() {
        CorporateAction placeholder = new CorporateAction();
        ReflectionTestUtils.setField(placeholder, "id", UUID.randomUUID());
        CorporateActionEntry nothing = entryOf(placeholder, UUID.randomUUID(), "0xddd", "0.00");
        CorporateAction ca = signedDue(List.of(nothing));
        ReflectionTestUtils.setField(nothing, "corporateActionId", ca.getId());

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.AWAITING_SETTLEMENT);
        verify(partyGate, never()).check(any(), anyString());
    }

    // ── aggregate on-ledger call: hold the action visibly, do not escalate ──────

    @Test
    @DisplayName("Canton aggregate call cannot exclude one holder: the action stays COMPUTED, held visibly with an audited event")
    void cantonAggregateIsHeldWholeAndVisibly() {
        UUID blocked = UUID.randomUUID();
        CorporateAction placeholder = new CorporateAction();
        ReflectionTestUtils.setField(placeholder, "id", UUID.randomUUID());
        CorporateActionEntry heldCandidate = entryOf(placeholder, blocked, "0xeee", "100.00");
        CorporateAction ca = signedDue(List.of(heldCandidate));
        ReflectionTestUtils.setField(heldCandidate, "corporateActionId", ca.getId());
        when(repository.findTokenStandardByCorpAction(ca.getId())).thenReturn("DAML_BOND_FIXED");
        when(partyGate.check(blocked, "0xeee")).thenReturn(List.of("is subject to an active §16 eWpG Sperrvermerk"));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(ca.getSettlementHoldReason()).contains("cannot exclude").contains("Sperrvermerk");
        assertThat(heldCandidate.getPayoutStatus()).isEqualTo(CorporateActionEntry.PayoutStatus.PAYABLE);
        verify(events).publishEvent(any(CorporateActionSettlementBlockedEvent.class));
        verify(events, never()).publishEvent(any(CorporateActionSettlementRequestedEvent.class));
        verify(entityTasks).open(eq(blocked), eq(CorporateActionBlocks.TASK_PAYOUT_HELD), anyString(), anyString(), eq(null));

        // the next run, same cause: still held, but the audit event is not repeated
        service.processDailyTransitions();
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(CorporateActionSettlementBlockedEvent.class));
    }

    @Test
    @DisplayName("a finality hold is recorded as a system hold (visible), not logged away")
    void finalityHoldIsRecorded() {
        CorporateAction placeholder = new CorporateAction();
        ReflectionTestUtils.setField(placeholder, "id", UUID.randomUUID());
        CorporateAction ca = signedDue(List.of(entryOf(placeholder, UUID.randomUUID(), "0xfff", "10.00")));
        when(finalityGate.check(any(), any(), any(), any())).thenReturn(new FinalityDecision.Blocked(
                de.makibytes.registerwerk.finality.api.GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM, assetId,
                FinalityLevel.FINALIZED, FinalityLevel.PROVISIONAL,
                FinalityDecision.Blocked.Reason.BELOW_REQUIRED, "block not final"));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(ca.getSettlementHoldReason()).contains("finality");
    }

    // ── manual settlement: the same per-entry rule ──────────────────────────────

    @Test
    @DisplayName("markSettledManually holds the ineligible entry and still records settlement for the others")
    void manualSettlementHoldsOnlyTheIneligibleEntry() {
        UUID clean = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        CorporateAction ca = dueAction(CorporateAction.Status.AWAITING_SETTLEMENT);
        CorporateActionEntry cleanEntry = entryOf(ca, clean, "0xaaa", "50.00");
        CorporateActionEntry blockedEntry = entryOf(ca, blocked, "0xbbb", "100.00");
        when(entryRepository.findByCorporateActionId(ca.getId())).thenReturn(List.of(cleanEntry, blockedEntry));
        when(partyGate.check(clean, "0xaaa")).thenReturn(List.of());
        when(partyGate.check(blocked, "0xbbb")).thenReturn(List.of("has an expired KYC"));

        service.markSettledManually(ca.getId(), "ref", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(blockedEntry.getPayoutStatus()).isEqualTo(CorporateActionEntry.PayoutStatus.HELD_BLOCKED);
        assertThat(cleanEntry.getPayoutStatus()).isEqualTo(CorporateActionEntry.PayoutStatus.PAYABLE);
        verify(settlementWriter).markSettled(eq(ca.getId()), eq("ref"), any(), eq("REGISTRY_ADMIN"));
    }

    // ── a registry-side hold is not the issuer's non-payment ────────────────────

    private CorporateAction overdueCoupon(CorporateAction.Status status) {
        CorporateAction ca = dueAction(status);
        ca.setPaymentDate(LocalDate.now().minusDays(60));
        ca.setCouponPaymentId(UUID.randomUUID());
        when(repository.findOverdueCoupons(any())).thenReturn(List.of(ca));
        AssetCouponPayment payment = new AssetCouponPayment();
        payment.setCouponStatus(CouponStatus.SCHEDULED);
        payment.setAssetId(assetId);
        when(couponPaymentRepository.findById(ca.getCouponPaymentId())).thenReturn(Optional.of(payment));
        return ca;
    }

    @Test
    @DisplayName("a coupon whose snapshot is blocked is neither OVERDUE nor MISSED, however late")
    void snapshotBlockedCouponIsNotFlagged() {
        CorporateAction ca = overdueCoupon(CorporateAction.Status.SNAPSHOT_BLOCKED);
        ca.setSnapshotBlockedReason("holder sync BLOCKED");

        service.processDailyTransitions();

        verify(couponPaymentRepository, never()).save(any(AssetCouponPayment.class));
    }

    @Test
    @DisplayName("a coupon held by the system (settlement hold) is neither OVERDUE nor MISSED")
    void heldCouponIsNotFlagged() {
        CorporateAction ca = overdueCoupon(CorporateAction.Status.COMPUTED);
        ca.setSettlementHoldReason("an on-ledger call cannot exclude an ineligible holder");

        service.processDailyTransitions();

        verify(couponPaymentRepository, never()).save(any(AssetCouponPayment.class));
    }

    @Test
    @DisplayName("a coupon that merely waits for the issuer is still flagged MISSED after the grace period (unchanged)")
    void issuerSideCouponIsStillFlagged() {
        CorporateAction ca = overdueCoupon(CorporateAction.Status.COMPUTED);

        service.processDailyTransitions();

        ArgumentCaptor<AssetCouponPayment> saved = ArgumentCaptor.forClass(AssetCouponPayment.class);
        verify(couponPaymentRepository).save(saved.capture());
        assertThat(saved.getValue().getCouponStatus()).isEqualTo(CouponStatus.MISSED);
        assertThat(ca.getSettlementHoldReason()).isNull();
    }
}
