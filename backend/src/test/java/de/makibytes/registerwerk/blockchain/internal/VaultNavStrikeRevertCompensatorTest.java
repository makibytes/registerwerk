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
        return strike;
    }

    private ChainEffectRecord effect(UUID entityId) {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "blockchain", "VAULT_NAV_STRIKE_CONFIRMED", "VaultNavStrike", entityId, CompensationCategory.INVERSE_FLIP,
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
        when(navStrikeRepository.findById(strikeId)).thenReturn(Optional.of(retracted));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(retracted.getNavPerShare());
        state.setLatestNavStrikeAt(t2);
        when(vaultStateRepository.findById(assetId)).thenReturn(Optional.of(state));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of(retracted, previous));

        CompensationOutcome outcome = compensator.compensate(effect(strikeId));

        assertThat(state.getLatestNavPerShare()).isEqualByComparingTo(previous.getNavPerShare());
        assertThat(state.getLatestNavStrikeAt()).isEqualTo(t1);
        verify(vaultStateRepository).save(state);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void nullsOutStateWhenThisWasTheFirstConfirmedStrike() {
        Instant t1 = Instant.ofEpochSecond(1000);
        VaultNavStrike retracted = strike(strikeId, 1L, new BigDecimal("1.00"), t1, true);
        when(navStrikeRepository.findById(strikeId)).thenReturn(Optional.of(retracted));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(retracted.getNavPerShare());
        state.setLatestNavStrikeAt(t1);
        when(vaultStateRepository.findById(assetId)).thenReturn(Optional.of(state));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of(retracted));

        compensator.compensate(effect(strikeId));

        assertThat(state.getLatestNavPerShare()).isNull();
        assertThat(state.getLatestNavStrikeAt()).isNull();
        verify(vaultStateRepository).save(state);
    }

    @Test
    void alreadySupersededStateIsNotApplicable() {
        Instant t1 = Instant.ofEpochSecond(1000);
        VaultNavStrike retracted = strike(strikeId, 1L, new BigDecimal("1.00"), t1, true);
        when(navStrikeRepository.findById(strikeId)).thenReturn(Optional.of(retracted));

        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setLatestNavPerShare(new BigDecimal("2.00"));
        state.setLatestNavStrikeAt(Instant.ofEpochSecond(9999));
        when(vaultStateRepository.findById(assetId)).thenReturn(Optional.of(state));

        CompensationOutcome outcome = compensator.compensate(effect(strikeId));

        verify(vaultStateRepository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void missingStrikeIsNotApplicable() {
        when(navStrikeRepository.findById(strikeId)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect(strikeId))).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
