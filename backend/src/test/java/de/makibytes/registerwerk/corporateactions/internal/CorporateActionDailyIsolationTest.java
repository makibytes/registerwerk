package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 0b H8: the daily corporate-action job holds no transaction of its own. Every action is processed in its own
 * {@code REQUIRES_NEW} transaction, so one failing action rolls back only itself - never the day's other snapshots
 * and settlements (the pattern of {@code TradeTimeoutProcessor}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Corporate-action daily job: one transaction per item (H8)")
class CorporateActionDailyIsolationTest {

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
    private PlatformTransactionManager txManager;

    private CorporateActionService service;

    @BeforeEach
    void setUp() {
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new CorporateActionService(repository, entryRepository, positionResolver, settlementWriter,
                couponPaymentRepository, proposalValidator, events, partyGate, entityTasks, finalityGate, freshnessGate,
                bondTermsRepository, CorporateActionTestSupport.systemRegisterClock(),
                new IsolatedTransactionExecutor(txManager));
        when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(freshnessGate.blockedReason(any(), any())).thenReturn(Optional.empty());
        when(finalityGate.check(any(), any(), any(), any())).thenReturn(new FinalityDecision.Allowed(FinalityLevel.FINALIZED));
        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findDueForSettlement(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(repository.findOverdueCoupons(any())).thenReturn(List.of());
    }

    private CorporateAction action(CorporateAction.Status status) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", UUID.randomUUID());
        ca.setAssetId(UUID.randomUUID());
        ca.setActionType(CorporateAction.ActionType.COUPON);
        ca.setCurrency("EUR");
        ca.setAmountPerUnit(new BigDecimal("0.05"));
        ca.setStatus(status);
        ca.setRecordDate(LocalDate.now().minusDays(2));
        ca.setPaymentDate(LocalDate.now());
        when(repository.findById(ca.getId())).thenReturn(Optional.of(ca));
        return ca;
    }

    @Test
    @DisplayName("a snapshot that blows up rolls back only itself; the next action is still snapshotted and committed")
    void failingSnapshotDoesNotRollBackTheOthers() {
        CorporateAction broken = action(CorporateAction.Status.ANNOUNCED);
        CorporateAction fine = action(CorporateAction.Status.ANNOUNCED);
        when(repository.findReadyToCompute(any())).thenReturn(List.of(broken, fine));
        AssetHolder holder = new AssetHolder();
        ReflectionTestUtils.setField(holder, "id", UUID.randomUUID());
        holder.setInvestorId(UUID.randomUUID());
        holder.setWalletAddress("0xabc");
        holder.setNominalAmount(new BigDecimal("1000"));
        when(positionResolver.resolve(org.mockito.ArgumentMatchers.eq(broken.getAssetId()), any()))
                .thenThrow(new IllegalStateException("resolver exploded"));
        when(positionResolver.resolve(org.mockito.ArgumentMatchers.eq(fine.getAssetId()), any()))
                .thenReturn(CorporateActionTestSupport.positionsOf(List.of(holder)));

        service.processDailyTransitions();

        assertThat(broken.getStatus()).isEqualTo(CorporateAction.Status.ANNOUNCED);
        assertThat(fine.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(d ->
                assertThat(d.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        InOrder order = inOrder(txManager);
        order.verify(txManager).getTransaction(any());
        order.verify(txManager).rollback(any());   // the broken action
        order.verify(txManager).getTransaction(any());
        order.verify(txManager).commit(any());     // the fine one
    }

    @Test
    @DisplayName("a settlement that blows up rolls back only itself; the next due action is still settled and committed")
    void failingSettlementDoesNotRollBackTheOthers() {
        CorporateAction broken = action(CorporateAction.Status.COMPUTED);
        CorporateAction fine = action(CorporateAction.Status.COMPUTED);
        CorporateActionEntry brokenEntry = CorporateActionTestSupport.entry(broken.getId(), "0xaaa", "1000", "50.00");
        CorporateActionEntry fineEntry = CorporateActionTestSupport.entry(fine.getId(), "0xbbb", "1000", "50.00");
        CorporateActionTestSupport.signedOff(broken, List.of(brokenEntry), UUID.randomUUID(), UUID.randomUUID());
        CorporateActionTestSupport.signedOff(fine, List.of(fineEntry), UUID.randomUUID(), UUID.randomUUID());
        when(repository.findDueForSettlement(any())).thenReturn(List.of(broken, fine));
        when(entryRepository.findByCorporateActionId(broken.getId())).thenThrow(new IllegalStateException("db hiccup"));
        when(entryRepository.findByCorporateActionId(fine.getId())).thenReturn(List.of(fineEntry));

        service.processDailyTransitions();

        assertThat(broken.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(fine.getStatus()).isEqualTo(CorporateAction.Status.AWAITING_SETTLEMENT);
        verify(txManager, times(1)).rollback(any());
        verify(txManager, times(1)).commit(any());
    }

    @Test
    @DisplayName("the job method itself opens no transaction: nothing is begun when there is nothing to do")
    void idleDayBeginsNoTransaction() {
        service.processDailyTransitions();

        verify(txManager, org.mockito.Mockito.never()).getTransaction(any());
    }

    @Test
    @DisplayName("on the Spring proxy the job method must not join any transaction (NOT_SUPPORTED), or REQUIRES_NEW would be pointless")
    void jobMethodDeclaresNoTransaction() throws Exception {
        org.springframework.transaction.annotation.Transactional tx = CorporateActionService.class
                .getMethod("processDailyTransitions")
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED);
    }
}
