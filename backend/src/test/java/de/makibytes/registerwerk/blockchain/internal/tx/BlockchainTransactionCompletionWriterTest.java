package de.makibytes.registerwerk.blockchain.internal.tx;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    @BeforeEach
    void setUp() {
        writer = new BlockchainTransactionCompletionWriter(repository, eventPublisher, new SimpleMeterRegistry(), chainEffectRecorder);
    }

    private BlockchainTransaction pendingTx(UUID chainConfigId) {
        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setTxHash("0xabc");
        tx.setChain("ETHEREUM");
        tx.setNetwork("MAINNET");
        tx.setChainConfigId(chainConfigId);
        tx.setStatus(BlockchainTransaction.Status.PENDING);
        return tx;
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
