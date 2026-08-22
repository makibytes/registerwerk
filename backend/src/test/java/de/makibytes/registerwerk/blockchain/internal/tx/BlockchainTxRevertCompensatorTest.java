package de.makibytes.registerwerk.blockchain.internal.tx;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlockchainTxRevertCompensator — the INVERSE_FLIP compensator for TX_COMPLETED")
class BlockchainTxRevertCompensatorTest {

    @Mock private BlockchainTransactionRepository repository;

    private BlockchainTxRevertCompensator compensator;

    private final UUID txId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new BlockchainTxRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "blockchain", "TX_COMPLETED", "BlockchainTransaction", txId, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    @DisplayName("advertises effectType TX_COMPLETED and category INVERSE_FLIP")
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("TX_COMPLETED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    @DisplayName("a SUCCESS transaction is reverted to PENDING and cleared block/completion fields")
    void compensateRevertsSuccessTransaction() {
        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setStatus(BlockchainTransaction.Status.SUCCESS);
        tx.setBlockNumber(100L);
        tx.setBlockHash("0xblock100");
        tx.setGasUsed(21_000L);
        tx.setCompletedAt(Instant.now());
        when(repository.findById(txId)).thenReturn(Optional.of(tx));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(tx);
        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        assertThat(tx.getBlockNumber()).isNull();
        assertThat(tx.getBlockHash()).isNull();
        assertThat(tx.getGasUsed()).isNull();
        assertThat(tx.getCompletedAt()).isNull();
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    @DisplayName("a transaction no longer SUCCESS (already reverted, or moved on) is NotApplicable, not re-reverted")
    void nonSuccessTransactionIsNotApplicable() {
        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setStatus(BlockchainTransaction.Status.PENDING);
        when(repository.findById(txId)).thenReturn(Optional.of(tx));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    @DisplayName("a vanished BlockchainTransaction row is NotApplicable")
    void missingRowIsNotApplicable() {
        when(repository.findById(txId)).thenReturn(Optional.empty());

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
