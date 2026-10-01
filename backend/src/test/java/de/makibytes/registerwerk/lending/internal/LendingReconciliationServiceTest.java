package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingReconciliationTask;
import de.makibytes.registerwerk.lending.api.LendingReconciliationTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Function;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Lending collateral balance guard and reconciliation (5B-10)")
class LendingReconciliationServiceTest {

    @Mock private LendingMarketRepository marketRepository;
    @Mock private LendingReconciliationTaskRepository taskRepository;
    @Mock private RepoMarketOnchainReader onchainReader;
    @Mock private LendingMarketService marketService;
    @Mock private DurableEvmTransactionGateway gateway;
    @Mock private LendingReleaseGate releaseGate;
    @Mock private ApplicationEventPublisher publisher;

    private LendingReconciliationService service;
    private LendingMarket market;
    private final UUID chainConfigId = UUID.randomUUID();
    private static final String MARKET = "0x" + "aa".repeat(20);
    private static final String TOKEN = "0x" + "bb".repeat(20);
    private static final String BORROWER = "0x" + "cc".repeat(20);

    @BeforeEach
    void setUp() {
        service = new LendingReconciliationService(marketRepository, taskRepository, onchainReader, marketService,
                gateway, releaseGate, publisher, new SimpleMeterRegistry(), new NoopTransactionManager());
        market = new LendingMarket();
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(MARKET);
        market.setCollateralTokenAddress(TOKEN);
        lenient().when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");
        lenient().when(marketService.requireMarket(market.getId())).thenReturn(market);
        lenient().when(taskRepository.save(any(LendingReconciliationTask.class))).thenAnswer(i -> {
            LendingReconciliationTask t = i.getArgument(0);
            if (t.getId() == null) org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });
    }

    @Test
    @DisplayName("balance below totalCollateral opens a task and marks the shortfall (market then reads PAUSED)")
    void guardOpensTaskOnShortfall() {
        when(onchainReader.tokenBalanceOf("ETHEREUM_SEPOLIA", TOKEN, MARKET)).thenReturn(BigInteger.valueOf(60));
        when(onchainReader.totalCollateral("ETHEREUM_SEPOLIA", MARKET)).thenReturn(BigInteger.valueOf(100));
        when(taskRepository.findFirstByMarketIdAndStatusIn(eq(market.getId()), any())).thenReturn(Optional.empty());

        service.checkMarket(market);

        ArgumentCaptor<LendingReconciliationTask> captor = ArgumentCaptor.forClass(LendingReconciliationTask.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().getShortfall()).isEqualTo(BigInteger.valueOf(40));
        assertThat(captor.getValue().getSource()).isEqualTo(LendingReconciliationTask.Source.BALANCE_GUARD);
        assertThat(market.isCollateralShortfall()).isTrue();
    }

    @Test
    @DisplayName("once the balance covers the recorded total the flag is cleared and the task resolved")
    void guardClearsShortfall() {
        market.setCollateralShortfall(true);
        LendingReconciliationTask task = new LendingReconciliationTask();
        when(onchainReader.tokenBalanceOf("ETHEREUM_SEPOLIA", TOKEN, MARKET)).thenReturn(BigInteger.valueOf(100));
        when(onchainReader.totalCollateral("ETHEREUM_SEPOLIA", MARKET)).thenReturn(BigInteger.valueOf(100));
        when(taskRepository.findFirstByMarketIdAndStatusIn(eq(market.getId()), any())).thenReturn(Optional.of(task));
        // the audit payload needs an id
        org.springframework.test.util.ReflectionTestUtils.setField(task, "id", UUID.randomUUID());

        service.checkMarket(market);

        assertThat(market.isCollateralShortfall()).isFalse();
        assertThat(task.getStatus()).isEqualTo(LendingReconciliationTask.Status.RESOLVED);
    }

    @Test
    @DisplayName("reconcile submits reconcileCollateral through the outbox and moves the task to SUBMITTED")
    void reconcileSubmitsThroughOutbox() {
        LendingReconciliationTask task = new LendingReconciliationTask();
        org.springframework.test.util.ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        when(taskRepository.findFirstByMarketIdAndStatusIn(eq(market.getId()), any())).thenReturn(Optional.of(task));
        when(gateway.submit(eq(chainConfigId), eq(MARKET), any(Function.class), anyMap())).thenReturn("0xtx");
        String ref = "0x" + "11".repeat(32);

        var result = service.reconcileCollateral(market.getId(), BORROWER, BigInteger.valueOf(10), ref,
                "Court order 12 O 3/26", UUID.randomUUID());

        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(gateway).submit(eq(chainConfigId), eq(MARKET), fn.capture(), anyMap());
        assertThat(fn.getValue().getName()).isEqualTo("reconcileCollateral");
        assertThat(fn.getValue().getInputParameters()).hasSize(3);
        assertThat(result.getStatus()).isEqualTo(LendingReconciliationTask.Status.SUBMITTED);
        assertThat(result.getReconcileTxHash()).isEqualTo("0xtx");
        assertThat(result.getForcedTransferRef()).isEqualTo(ref);
    }

    @Test
    @DisplayName("reconcile refuses a malformed reference, a missing legal basis and a market without a task")
    void reconcileValidatesInput() {
        assertThatThrownBy(() -> service.reconcileCollateral(market.getId(), BORROWER, BigInteger.ONE, "0x12",
                "basis", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileCollateral(market.getId(), BORROWER, BigInteger.ONE,
                "0x" + "11".repeat(32), " ", null)).isInstanceOf(IllegalArgumentException.class);
        when(taskRepository.findFirstByMarketIdAndStatusIn(eq(market.getId()), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcileCollateral(market.getId(), BORROWER, BigInteger.ONE,
                "0x" + "11".repeat(32), "basis", null)).isInstanceOf(IllegalStateException.class);
        verify(gateway, never()).submit(any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("setBorrowPaused goes through the outbox")
    void pauseSubmitsThroughOutbox() {
        when(gateway.submit(eq(chainConfigId), eq(MARKET), any(Function.class), anyMap())).thenReturn("0xpause");

        String tx = service.setBorrowPaused(market.getId(), true, "forced transfer", UUID.randomUUID());

        assertThat(tx).isEqualTo("0xpause");
        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(gateway).submit(eq(chainConfigId), eq(MARKET), fn.capture(), any(Map.class));
        assertThat(fn.getValue().getName()).isEqualTo("setBorrowPaused");
    }

    private static final class NoopTransactionManager implements org.springframework.transaction.PlatformTransactionManager {
        @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition d) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }
        @Override public void commit(org.springframework.transaction.TransactionStatus status) { }
        @Override public void rollback(org.springframework.transaction.TransactionStatus status) { }
    }

    @Test
    @DisplayName("N9: the scheduled balance guard does not hold a transaction across RPC reads")
    void balanceGuardRunsWithoutTransaction() throws Exception {
        org.springframework.transaction.annotation.Transactional tx =
                org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                        LendingReconciliationService.class.getMethod("runBalanceGuard"),
                        org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED);
    }
}
