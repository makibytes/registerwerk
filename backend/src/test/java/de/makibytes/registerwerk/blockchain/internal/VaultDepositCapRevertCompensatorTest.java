package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultDepositCapRevertCompensatorTest {

    @Mock private AssetVaultStateRepository repository;

    private VaultDepositCapRevertCompensator compensator;
    private final UUID assetId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new VaultDepositCapRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 500L, "0xblock500", "0xcaptx", null,
                "blockchain", VaultDepositCapRevertCompensator.EFFECT_TYPE, "AssetVaultState", assetId, assetId,
                CompensationCategory.INVERSE_FLIP, Map.of("depositCap", "100"), Map.of("depositCap", "500"),
                null, null, "COMPENSATING", 1, Instant.now());
    }

    private ChainEffectRecord effect(long blockNumber, String blockHash, String txHash,
                                     BigInteger before, UUID beforeChainConfigId,
                                     Long beforeBlockNumber, String beforeBlockHash, String beforeTxHash,
                                     BigInteger after) {
        java.util.HashMap<String, Object> beforeState = new java.util.HashMap<>();
        beforeState.put("depositCap", before != null ? before.toString() : null);
        beforeState.put("chainConfigId", beforeChainConfigId != null ? beforeChainConfigId.toString() : null);
        beforeState.put("blockNumber", beforeBlockNumber);
        beforeState.put("blockHash", beforeBlockHash);
        beforeState.put("txHash", beforeTxHash);
        return new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, blockNumber, blockHash, txHash, null,
                "blockchain", VaultDepositCapRevertCompensator.EFFECT_TYPE,
                "AssetVaultState", assetId, assetId, CompensationCategory.INVERSE_FLIP,
                beforeState, Map.of("depositCap", after.toString()), null, null,
                "COMPENSATING", 1, Instant.now());
    }

    private AssetVaultState confirmedState(String blockHash) {
        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setDepositCap(BigInteger.valueOf(500));
        state.setDepositCapChainConfigId(chainConfigId);
        state.setDepositCapBlockNumber(500L);
        state.setDepositCapBlockHash(blockHash);
        state.setDepositCapConfirmedTxHash("0xcaptx");
        return state;
    }

    @Test
    void restoresPreimageAndReturnsRetractedIntentToPending() {
        AssetVaultState state = confirmedState("0xblock500");
        when(repository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(state.getDepositCap()).isEqualTo(BigInteger.valueOf(100));
        assertThat(state.getPendingDepositCap()).isEqualTo(BigInteger.valueOf(500));
        assertThat(state.getDepositCapTxHash()).isEqualTo("0xcaptx");
        assertThat(state.getDepositCapBlockHash()).isNull();
        assertThat(state.getDepositCapConfirmedTxHash()).isNull();
        verify(repository).save(state);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void oldEffectCannotUndoReplacementBlockValue() {
        AssetVaultState state = confirmedState("0xreplacement");
        when(repository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
        assertThat(state.getDepositCap()).isEqualTo(BigInteger.valueOf(500));
    }

    @Test
    void matchingOccurrenceWithUnexpectedValueFailsClosed() {
        AssetVaultState state = confirmedState("0xblock500");
        state.setDepositCap(BigInteger.valueOf(999));
        when(repository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.Failed.class);
    }

    @Test
    void partialPendingIntentFailsClosedBeforeProjectionMutation() {
        AssetVaultState state = confirmedState("0xblock500");
        state.setPendingDepositCap(BigInteger.valueOf(900));
        when(repository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(outcome).isInstanceOf(CompensationOutcome.Failed.class);
        assertThat(state.getDepositCap()).isEqualTo(BigInteger.valueOf(500));
        assertThat(state.getDepositCapBlockHash()).isEqualTo("0xblock500");
        verify(repository, never()).save(any());
    }

    @Test
    void sequentialOrphanedCapsUnwindLifoWithTheirPriorProvenance() {
        UUID originalChainConfigId = chainConfigId;
        ChainEffectRecord first = effect(
                500L, "0xblock500", "0xcap1",
                BigInteger.valueOf(100), originalChainConfigId, 100L, "0xblock100", "0xoriginal",
                BigInteger.valueOf(500));
        ChainEffectRecord second = effect(
                600L, "0xblock600", "0xcap2",
                BigInteger.valueOf(500), chainConfigId, 500L, "0xblock500", "0xcap1",
                BigInteger.valueOf(900));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setDepositCap(BigInteger.valueOf(900));
        state.setDepositCapChainConfigId(chainConfigId);
        state.setDepositCapBlockNumber(600L);
        state.setDepositCapBlockHash("0xblock600");
        state.setDepositCapConfirmedTxHash("0xcap2");
        when(repository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));

        assertThat(compensator.compensate(second)).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(state.getDepositCap()).isEqualTo(BigInteger.valueOf(500));
        assertThat(state.getDepositCapBlockNumber()).isEqualTo(500L);
        assertThat(state.getDepositCapBlockHash()).isEqualTo("0xblock500");
        assertThat(state.getDepositCapConfirmedTxHash()).isEqualTo("0xcap1");

        assertThat(compensator.compensate(first)).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(state.getDepositCap()).isEqualTo(BigInteger.valueOf(100));
        assertThat(state.getDepositCapChainConfigId()).isEqualTo(originalChainConfigId);
        assertThat(state.getDepositCapBlockNumber()).isEqualTo(100L);
        assertThat(state.getDepositCapBlockHash()).isEqualTo("0xblock100");
        assertThat(state.getDepositCapConfirmedTxHash()).isEqualTo("0xoriginal");
        assertThat(state.getPendingDepositCap()).isEqualTo(BigInteger.valueOf(900));
        assertThat(state.getDepositCapTxHash()).isEqualTo("0xcap2");
    }
}
