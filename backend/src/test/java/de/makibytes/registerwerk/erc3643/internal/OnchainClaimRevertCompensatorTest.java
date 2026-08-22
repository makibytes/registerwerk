package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
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
@DisplayName("OnchainClaimRevertCompensator — INVERSE_FLIP compensator for ERC3643_CLAIM_CONFIRMED")
class OnchainClaimRevertCompensatorTest {

    @Mock private OnchainClaimRepository claimRepository;

    private OnchainClaimRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new OnchainClaimRevertCompensator(claimRepository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "erc3643", "ERC3643_CLAIM_CONFIRMED", "OnchainClaim", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("ERC3643_CLAIM_CONFIRMED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void revertsConfirmedClaimToUnconfirmed() {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(id);
        claim.setConfirmed(true);
        claim.setChainConfigId(UUID.randomUUID());
        claim.setBlockNumber(100L);
        when(claimRepository.findById(id)).thenReturn(Optional.of(claim));

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(claim.isConfirmed()).isFalse();
        assertThat(claim.getChainConfigId()).isNull();
        assertThat(claim.getBlockNumber()).isNull();
        verify(claimRepository).save(claim);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void alreadyUnconfirmedClaimIsNotApplicable() {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(id);
        claim.setConfirmed(false);
        when(claimRepository.findById(id)).thenReturn(Optional.of(claim));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(claimRepository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void missingClaimIsNotApplicable() {
        when(claimRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
