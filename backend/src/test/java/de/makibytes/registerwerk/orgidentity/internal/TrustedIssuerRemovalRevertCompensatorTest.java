package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
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
class TrustedIssuerRemovalRevertCompensatorTest {

    @Mock private EcosystemTrustedIssuerRepository repository;

    private TrustedIssuerRemovalRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new TrustedIssuerRemovalRevertCompensator(repository);
    }

    private ChainEffectRecord effect(String blockHash) {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, blockHash, "0xremove", null,
                "orgidentity", "TRUSTED_ISSUER_REMOVED", "EcosystemTrustedIssuer", id, null,
                CompensationCategory.INVERSE_FLIP, null, null, null, null,
                "COMPENSATING", 1, Instant.now());
    }

    private EcosystemTrustedIssuer confirmed(String blockHash) {
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setChainConfigId(chainConfigId);
        issuer.setStatus(TrustedIssuerStatus.REMOVED);
        issuer.setRemovedTx("0xremove");
        issuer.setRemovedBlockNumber(100L);
        issuer.setRemovedBlockHash(blockHash);
        return issuer;
    }

    @Test
    void exactIncarnationReturnsToFailClosedVerification() {
        EcosystemTrustedIssuer issuer = confirmed("0xaaa");
        when(repository.findById(id)).thenReturn(Optional.of(issuer));

        CompensationOutcome outcome = compensator.compensate(effect("0xaaa"));

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(issuer.getStatus()).isEqualTo(TrustedIssuerStatus.REMOVAL_PENDING);
        assertThat(issuer.getRemovedTx()).isEqualTo("0xremove");
        assertThat(issuer.getRemovedBlockHash()).isNull();
        verify(repository).save(issuer);
    }

    @Test
    void staleOldCompensationCannotUndoNewerConfirmation() {
        EcosystemTrustedIssuer issuer = confirmed("0xbbb");
        when(repository.findById(id)).thenReturn(Optional.of(issuer));

        CompensationOutcome outcome = compensator.compensate(effect("0xaaa"));

        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
        assertThat(issuer.getStatus()).isEqualTo(TrustedIssuerStatus.REMOVED);
        verify(repository, never()).save(any());
    }
}
