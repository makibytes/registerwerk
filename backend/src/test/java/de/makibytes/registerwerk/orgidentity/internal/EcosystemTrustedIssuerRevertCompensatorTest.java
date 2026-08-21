package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
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
@DisplayName("EcosystemTrustedIssuerRevertCompensator — INVERSE_FLIP compensator for TRUSTED_ISSUER_ADDED")
class EcosystemTrustedIssuerRevertCompensatorTest {

    @Mock private EcosystemTrustedIssuerRepository repository;

    private EcosystemTrustedIssuerRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new EcosystemTrustedIssuerRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "orgidentity", "TRUSTED_ISSUER_ADDED", "EcosystemTrustedIssuer", id, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("TRUSTED_ISSUER_ADDED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateRevertsActiveIssuer() {
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setStatus(MemberWalletStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(issuer));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(issuer);
        assertThat(issuer.getStatus()).isEqualTo(MemberWalletStatus.PENDING);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void nonActiveIssuerIsNotApplicable() {
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setStatus(MemberWalletStatus.REMOVED);
        when(repository.findById(id)).thenReturn(Optional.of(issuer));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
