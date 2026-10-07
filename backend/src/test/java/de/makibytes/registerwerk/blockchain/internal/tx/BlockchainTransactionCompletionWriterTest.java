package de.makibytes.registerwerk.blockchain.internal.tx;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlockchainTransactionCompletionWriter — TX_COMPLETED chain-effect journalling")
class BlockchainTransactionCompletionWriterTest {

    @Mock private BlockchainTransactionRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ChainEffectRecorder chainEffectRecorder;

    private BlockchainTransactionCompletionWriter writer;
    private final PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @BeforeEach
    void setUp() {
        writer = new BlockchainTransactionCompletionWriter(repository, eventPublisher, meters, chainEffectRecorder);
    }

    private BlockchainTransaction pendingTx(UUID chainConfigId) {
        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setTxHash("0xabc");
        tx.setChain("ETHEREUM");
        tx.setNetwork("MAINNET");
        tx.setChainConfigId(chainConfigId);
        tx.setStatus(BlockchainTransaction.Status.PENDING);
        org.springframework.test.util.ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        org.mockito.Mockito.lenient().when(repository.findByIdForUpdate(tx.getId())).thenReturn(java.util.Optional.of(tx));
        return tx;
    }

    @Test
    @DisplayName("7A-06: a stale TIMEOUT write after SUCCESS is skipped - no overwrite, no second audit event")
    void staleTimeoutAfterSuccessIsSkipped() {
        BlockchainTransaction observedByPollerB = pendingTx(UUID.randomUUID());
        observedByPollerB.setChainConfigId(UUID.randomUUID());
        // poller A completed the row meanwhile: the locked DB row is SUCCESS, B still holds the PENDING copy
        BlockchainTransaction dbRow = new BlockchainTransaction();
        dbRow.setStatus(BlockchainTransaction.Status.SUCCESS);
        org.mockito.Mockito.when(repository.findByIdForUpdate(observedByPollerB.getId()))
                .thenReturn(java.util.Optional.of(dbRow));

        writer.markTimeout(observedByPollerB, 900);

        assertThat(dbRow.getStatus()).isEqualTo(BlockchainTransaction.Status.SUCCESS);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("a SUCCESS completion with a resolved chainConfigId journals a TX_COMPLETED chain effect")
    void successWithChainConfigIdJournalsEffect() {
        UUID chainConfigId = UUID.randomUUID();
        BlockchainTransaction tx = pendingTx(chainConfigId);
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setBlockNumber("0x64");
        receipt.setBlockHash("0xblock100");
        receipt.setGasUsed("0x5208");

        writer.complete(tx, receipt);

        ArgumentCaptor<ChainEffectDescriptor> captor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(captor.capture());
        ChainEffectDescriptor descriptor = captor.getValue();
        assertThat(descriptor.chainConfigId()).isEqualTo(chainConfigId);
        assertThat(descriptor.blockNumber()).isEqualTo(100L);
        assertThat(descriptor.effectType()).isEqualTo("TX_COMPLETED");
        assertThat(descriptor.entityType()).isEqualTo("BlockchainTransaction");
        assertThat(descriptor.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    @DisplayName("P4B-5: a receipt for a TIMEOUT row counts registerwerk_blockchain_tx_late_mined_total by outcome")
    void lateMinedReceiptIncrementsTheLateMinedCounter() {
        BlockchainTransaction tx = pendingTx(UUID.randomUUID());
        tx.setStatus(BlockchainTransaction.Status.TIMEOUT);
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setBlockNumber("0x64");
        receipt.setBlockHash("0xblock100");
        receipt.setGasUsed("0x5208");

        writer.complete(tx, receipt);

        assertThat(meters.get("registerwerk.blockchain.tx.late_mined").tag("outcome", "SUCCESS").counter().count())
                .isEqualTo(1.0);
        // BlockchainTxLateMined alert: increase(registerwerk_blockchain_tx_late_mined_total[30m]) > 0
        assertThat(meters.scrape()).contains("registerwerk_blockchain_tx_late_mined_total{outcome=\"SUCCESS\"} 1.0");
    }

    @Test
    @DisplayName("a normal completion leaves the late-mined counter alone but records the submission-to-confirmation latency histogram")
    void normalCompletionRecordsLatencyNotLateMined() {
        BlockchainTransaction tx = pendingTx(UUID.randomUUID());
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setBlockNumber("0x64");
        receipt.setBlockHash("0xblock100");
        receipt.setGasUsed("0x5208");

        writer.complete(tx, receipt);

        assertThat(meters.find("registerwerk.blockchain.tx.late_mined").counter()).isNull();
        assertThat(meters.get("registerwerk.blockchain.tx.confirmation.latency")
                .tag("chain", "ETHEREUM").tag("outcome", "SUCCESS").timer().count()).isEqualTo(1L);
        // BlockchainTxConfirmationLatencyHigh: histogram_quantile over ..._seconds_bucket, grouped by chain
        assertThat(meters.scrape())
                .contains("registerwerk_blockchain_tx_confirmation_latency_seconds_bucket{chain=\"ETHEREUM\",outcome=\"SUCCESS\",le=");
    }

    @Test
    @DisplayName("a FAILED completion never journals a chain effect — nothing to undo")
    void failedCompletionDoesNotJournal() {
        BlockchainTransaction tx = pendingTx(UUID.randomUUID());
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x0");
        receipt.setBlockNumber("0x64");
        receipt.setGasUsed("0x5208");

        writer.complete(tx, receipt);

        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a SUCCESS completion with no resolved chainConfigId fails closed")
    void successWithoutChainConfigIdFailsClosed() {
        BlockchainTransaction tx = pendingTx(null);
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setBlockNumber("0x64");
        receipt.setBlockHash("0xblock100");
        receipt.setGasUsed("0x5208");

        assertThatThrownBy(() -> writer.complete(tx, receipt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provenance");

        verify(chainEffectRecorder, never()).recordFinalized(any());
    }
}
