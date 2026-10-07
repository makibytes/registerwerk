package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSnapshotBlockedEvent;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2-18: the record-date snapshot and settlement confirmation refuse a non-reconciled register visibly. */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionService register-freshness gate (T2-18)")
class CorporateActionSnapshotGateTest {

    @Mock private CorporateActionRepository repository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private RecordDatePositionResolver positionResolver;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository bondTermsRepository;
    @Mock private CorporateActionSettlementWriter settlementWriter;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private CorporateActionProposalValidator proposalValidator;
    @Mock private ApplicationEventPublisher events;
    @Mock private PartyEligibilityGate partyGate;
    @Mock private EntityTaskPort entityTasks;
    @Mock private FinalityGate finalityGate;
    @Mock private RegisterFreshnessGate freshnessGate;

    private CorporateActionService service;

    @BeforeEach
    void setUp() {
        service = new CorporateActionService(repository, entryRepository, positionResolver, settlementWriter,
                couponPaymentRepository, proposalValidator, events, partyGate, entityTasks, finalityGate, freshnessGate,
                bondTermsRepository, CorporateActionTestSupport.systemRegisterClock(),
                CorporateActionTestSupport.directTransactions());
    }

    private static CorporateAction dueAction(CorporateAction.Status status) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", UUID.randomUUID());
        ca.setAssetId(UUID.randomUUID());
        ca.setStatus(status);
        ca.setAmountPerUnit(new BigDecimal("0.05"));
        ca.setCurrency("EUR");
        ca.setRecordDate(LocalDate.now());
        ca.setPaymentDate(LocalDate.now().plusDays(5));
        return ca;
    }

    private void givenDailyRun(CorporateAction ca) {
        when(repository.findById(ca.getId())).thenReturn(Optional.of(ca));
        when(repository.findReadyToCompute(any())).thenReturn(List.of(ca));
        when(repository.findDueForSettlement(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
    }

    @Test
    @DisplayName("blocked register → SNAPSHOT_BLOCKED with reason, audited, no entries")
    void blockedRegister_marksSnapshotBlocked() {
        CorporateAction ca = dueAction(CorporateAction.Status.ANNOUNCED);
        givenDailyRun(ca);
        when(freshnessGate.blockedReason(ca.getAssetId(), ca.getRecordDate()))
                .thenReturn(Optional.of("holder sync BLOCKED, unmapped wallets: 0xpool"));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.SNAPSHOT_BLOCKED);
        assertThat(ca.getSnapshotBlockedReason()).contains("0xpool");
        verify(entryRepository, never()).save(any());
        verify(events).publishEvent(new CorporateActionSnapshotBlockedEvent(ca.getId(), ca.getAssetId(),
                "holder sync BLOCKED, unmapped wallets: 0xpool"));
    }

    @Test
    @DisplayName("still blocked for the same reason on the next run → not re-audited")
    void stillBlocked_sameReason_notReAudited() {
        CorporateAction ca = dueAction(CorporateAction.Status.SNAPSHOT_BLOCKED);
        ca.setSnapshotBlockedReason("stale");
        givenDailyRun(ca);
        when(freshnessGate.blockedReason(any(), any())).thenReturn(Optional.of("stale"));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.SNAPSHOT_BLOCKED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("reconciled register → snapshot taken; nominee-pool entry HELD_LOOK_THROUGH and outside total")
    void reconciled_snapshotsWithPoolEntryHeld() {
        CorporateAction ca = dueAction(CorporateAction.Status.SNAPSHOT_BLOCKED);
        ca.setSnapshotBlockedReason("stale");
        givenDailyRun(ca);
        when(freshnessGate.blockedReason(any(), any())).thenReturn(Optional.empty());
        AssetHolder investor = new AssetHolder();
        ReflectionTestUtils.setField(investor, "id", UUID.randomUUID());
        investor.setInvestorId(UUID.randomUUID());
        investor.setWalletAddress("0xinv");
        investor.setNominalAmount(new BigDecimal("60"));
        AssetHolder pool = new AssetHolder();
        ReflectionTestUtils.setField(pool, "id", UUID.randomUUID());
        pool.setInvestorId(UUID.randomUUID());
        pool.setWalletAddress("0xpool");
        pool.setNominalAmount(new BigDecimal("40"));
        pool.setHolderKind(HolderKind.NOMINEE_POOL);
        when(positionResolver.resolve(org.mockito.ArgumentMatchers.eq(ca.getAssetId()), any())).thenReturn(CorporateActionTestSupport.positionsOf(List.of(investor, pool)));

        service.processDailyTransitions();

        ArgumentCaptor<CorporateActionEntry> entries = ArgumentCaptor.forClass(CorporateActionEntry.class);
        verify(entryRepository, times(2)).save(entries.capture());
        assertThat(entries.getAllValues()).filteredOn(e -> e.getWalletAddress().equals("0xpool"))
                .singleElement()
                .extracting(CorporateActionEntry::getPayoutStatus)
                .isEqualTo(CorporateActionEntry.PayoutStatus.HELD_LOOK_THROUGH);
        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(ca.getSnapshotBlockedReason()).isNull();
        assertThat(ca.getTotalAmount()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("settlement confirmation is refused while the register is not reconciled")
    void confirmSettlement_refusedWhileBlocked() {
        CorporateAction ca = dueAction(CorporateAction.Status.COMPUTED);
        List<CorporateActionEntry> computed = List.of(CorporateActionTestSupport.entry(ca.getId(), "0xaaa", "1000", "50.00"));
        CorporateActionTestSupport.computed(ca, computed);
        ca.setIssuerAttestedAt(Instant.now());
        ca.setIssuerAttestedBy(UUID.randomUUID());
        ca.setIssuerAttestedDigest(ca.getPayoutDigest());
        when(entryRepository.findByCorporateActionId(ca.getId())).thenReturn(computed);
        when(repository.findById(ca.getId())).thenReturn(Optional.of(ca));
        when(freshnessGate.blockedReason(any(), any())).thenReturn(Optional.of("holder sync BLOCKED"));

        assertThatThrownBy(() -> service.confirmSettlementAsOperator(ca.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holder sync BLOCKED");
        assertThat(ca.getDualControlApproverId()).isNull();
        verify(repository, never()).save(any());
    }
}
