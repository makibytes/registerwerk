package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
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
class OnchainClaimRevocationRevertCompensatorTest {

    @Mock private OnchainClaimRepository repository;

    private OnchainClaimRevocationRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new OnchainClaimRevocationRevertCompensator(repository);
    }

    private ChainEffectRecord effect(String blockHash) {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 200L, blockHash, "0xrevoke", null,
                "erc3643", "ERC3643_CLAIM_REVOKED", "OnchainClaim", id, null,
                CompensationCategory.INVERSE_FLIP, null, null, null, null,
                "COMPENSATING", 1, Instant.now());
    }

    private OnchainClaim revokedAt(String blockHash) {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(id);
        claim.setConfirmed(true);
        claim.setRevokedAt(Instant.now());
        claim.setRevocationTxHash("0xrevoke");
        claim.setRevocationChainConfigId(chainConfigId);
        claim.setRevocationBlockNumber(200L);
        claim.setRevocationBlockHash(blockHash);
        return claim;
    }

    @Test
    void exactRevocationIncarnationIsReopened() {
        OnchainClaim claim = revokedAt("0xA");
        when(repository.findById(id)).thenReturn(Optional.of(claim));

        assertThat(compensator.compensate(effect("0xA")))
                .isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(claim.getRevokedAt()).isNull();
        assertThat(claim.getRevocationTxHash()).isEqualTo("0xrevoke");
        assertThat(claim.getRevocationChainConfigId()).isNull();
        assertThat(claim.getRevocationBlockNumber()).isNull();
        assertThat(claim.getRevocationBlockHash()).isNull();
        verify(repository).save(claim);
    }

    @Test
    void staleRevocationCannotReopenNewerCanonicalRevocation() {
        OnchainClaim claim = revokedAt("0xNEW");
        when(repository.findById(id)).thenReturn(Optional.of(claim));

        assertThat(compensator.compensate(effect("0xOLD")))
                .isInstanceOf(CompensationOutcome.NotApplicable.class);
        assertThat(claim.getRevokedAt()).isNotNull();
        verify(repository, never()).save(any());
    }
}
