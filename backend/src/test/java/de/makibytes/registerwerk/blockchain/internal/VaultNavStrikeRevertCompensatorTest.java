package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VaultNavStrikeRevertCompensator — INVERSE_FLIP compensator for VAULT_NAV_STRIKE_CONFIRMED")
class VaultNavStrikeRevertCompensatorTest {

    @Mock private VaultNavStrikeRepository navStrikeRepository;
    @Mock private AssetVaultStateRepository vaultStateRepository;

    private VaultNavStrikeRevertCompensator compensator;
    private final UUID assetId = UUID.randomUUID();
    private final UUID strikeId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new VaultNavStrikeRevertCompensator(navStrikeRepository, vaultStateRepository);
    }

    private VaultNavStrike strike(UUID id, long strikeIdSeq, BigDecimal nav, Instant effectiveAt, boolean confirmed) {
        VaultNavStrike strike = new VaultNavStrike();
        ReflectionTestUtils.setField(strike, "id", id);
        strike.setAssetId(assetId);
        strike.setStrikeId(strikeIdSeq);
        strike.setNavPerShare(nav);
        strike.setEffectiveAt(effectiveAt);
        strike.setConfirmed(confirmed);
        if (confirmed) {
            strike.setChainConfigId(chainConfigId);
            strike.setBlockNumber(100L);
            strike.setBlockHash("0xhash");
            strike.setTxHash("0xtxhash");
        }
        return strike;
    }

    private ChainEffectRecord effect(UUID entityId) {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, "0xhash", "0xtxhash", null,
                "blockchain", "VAULT_NAV_STRIKE_CONFIRMED", "VaultNavStrike", entityId, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("VAULT_NAV_STRIKE_CONFIRMED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void revertsToPreviousConfirmedStrike() {
        Instant t1 = Instant.ofEpochSecond(1000);
        Instant t2 = Instant.ofEpochSecond(2000);
        VaultNavStrike previous = strike(UUID.randomUUID(), 1L, new BigDecimal("1.00"), t1, true);
        VaultNavStrike retracted = strike(strikeId, 2L, new BigDecimal("1.10"), t2, true);
        when(navStrikeRepository.findByIdForUpdate(strikeId)).thenReturn(Optional.of(retracted));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(retracted.getNavPerShare());
        state.setLatestNavStrikeAt(t2);
        state.setLatestNavStrikeId(retracted.getId());
        when(vaultStateRepository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of(retracted, previous));

        CompensationOutcome outcome = compensator.compensate(effect(strikeId));

        assertThat(state.getLatestNavPerShare()).isEqualByComparingTo(previous.getNavPerShare());
        assertThat(state.getLatestNavStrikeAt()).isEqualTo(t1);
        assertThat(state.getLatestNavStrikeId()).isEqualTo(previous.getId());
        assertThat(retracted.isConfirmed()).isFalse();
        assertThat(retracted.getBlockHash()).isNull();
        verify(navStrikeRepository).save(retracted);
        verify(vaultStateRepository).save(state);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void staleEffectCannotUndoReminedStrikeIncarnation() {
        Instant effectiveAt = Instant.ofEpochSecond(1000);
        VaultNavStrike retracted = strike(strikeId, 1L, new BigDecimal("1.00"), effectiveAt, true);
        retracted.setBlockHash("0xreplacement");
        when(navStrikeRepository.findByIdForUpdate(strikeId)).thenReturn(Optional.of(retracted));

        CompensationOutcome outcome = compensator.compensate(effect(strikeId));

        verify(navStrikeRepository, never()).save(any());
        verify(vaultStateRepository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void nullsOutStateWhenThisWasTheFirstConfirmedStrike() {
        Instant t1 = Instant.ofEpochSecond(1000);
        VaultNavStrike retracted = strike(strikeId, 1L, new BigDecimal("1.00"), t1, true);
        when(navStrikeRepository.findByIdForUpdate(strikeId)).thenReturn(Optional.of(retracted));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(retracted.getNavPerShare());
        state.setLatestNavStrikeAt(t1);
        state.setLatestNavStrikeId(retracted.getId());
        when(vaultStateRepository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of(retracted));

        compensator.compensate(effect(strikeId));

        assertThat(state.getLatestNavPerShare()).isNull();
        assertThat(state.getLatestNavStrikeAt()).isNull();
        assertThat(state.getLatestNavStrikeId()).isNull();
        verify(vaultStateRepository).save(state);
    }

    @Test
    void alreadySupersededStateClearsOrphanConfirmationWithoutMutatingProjection() {
        Instant t1 = Instant.ofEpochSecond(1000);
        VaultNavStrike retracted = strike(strikeId, 1L, new BigDecimal("1.00"), t1, true);
        when(navStrikeRepository.findByIdForUpdate(strikeId)).thenReturn(Optional.of(retracted));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(new BigDecimal("2.00"));
        state.setLatestNavStrikeAt(Instant.ofEpochSecond(9999));
        state.setLatestNavStrikeId(UUID.randomUUID());
        when(vaultStateRepository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of(retracted));

        CompensationOutcome outcome = compensator.compensate(effect(strikeId));

        verify(vaultStateRepository, never()).save(any());
        verify(navStrikeRepository).save(retracted);
        assertThat(retracted.isConfirmed()).isFalse();
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void olderRetryWithSameEffectiveAtCannotWipeNewerStrikeProjection() {
        Instant sharedEffectiveAt = Instant.ofEpochSecond(1000);
        VaultNavStrike older = strike(strikeId, 1L, new BigDecimal("1.00"), sharedEffectiveAt, true);
        VaultNavStrike newer = strike(UUID.randomUUID(), 2L, new BigDecimal("1.10"), sharedEffectiveAt, true);
        when(navStrikeRepository.findByIdForUpdate(strikeId)).thenReturn(Optional.of(older));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(newer.getNavPerShare());
        state.setLatestNavStrikeAt(sharedEffectiveAt);
        state.setLatestNavStrikeId(newer.getId());
        state.setLatestNavReportHash(newer.getReportHash());
        when(vaultStateRepository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of(older, newer));

        CompensationOutcome outcome = compensator.compensate(effect(strikeId));

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(state.getLatestNavStrikeId()).isEqualTo(newer.getId());
        assertThat(state.getLatestNavPerShare()).isEqualByComparingTo(newer.getNavPerShare());
        assertThat(older.isConfirmed()).isFalse();
        verify(navStrikeRepository).save(older);
        verify(vaultStateRepository, never()).save(any());
    }

    @Test
    void projectionOwnerThatIsNotHighestConfirmedFailsClosed() {
        Instant sharedEffectiveAt = Instant.ofEpochSecond(1000);
        VaultNavStrike inconsistentOwner = strike(
                strikeId, 1L, new BigDecimal("1.00"), sharedEffectiveAt, true);
        VaultNavStrike newer = strike(
                UUID.randomUUID(), 2L, new BigDecimal("1.10"), sharedEffectiveAt, true);
        when(navStrikeRepository.findByIdForUpdate(strikeId)).thenReturn(Optional.of(inconsistentOwner));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(inconsistentOwner.getNavPerShare());
        state.setLatestNavStrikeAt(sharedEffectiveAt);
        state.setLatestNavStrikeId(inconsistentOwner.getId());
        when(vaultStateRepository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.of(state));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId))
                .thenReturn(List.of(inconsistentOwner, newer));

        CompensationOutcome outcome = compensator.compensate(effect(strikeId));

        assertThat(outcome).isInstanceOf(CompensationOutcome.Failed.class);
        assertThat(inconsistentOwner.isConfirmed()).isTrue();
        verify(navStrikeRepository, never()).save(any());
        verify(vaultStateRepository, never()).save(any());
    }

    @Test
    void missingStrikeIsNotApplicable() {
        when(navStrikeRepository.findByIdForUpdate(strikeId)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect(strikeId))).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
