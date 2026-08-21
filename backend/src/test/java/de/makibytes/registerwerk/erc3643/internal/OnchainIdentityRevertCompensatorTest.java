package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
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
@DisplayName("OnchainIdentityRevertCompensator — INVERSE_FLIP compensator for ONCHAIN_IDENTITY_DEPLOYED")
class OnchainIdentityRevertCompensatorTest {

    @Mock private OnchainIdentityRepository repository;

    private OnchainIdentityRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new OnchainIdentityRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "erc3643", "ONCHAIN_IDENTITY_DEPLOYED", "OnchainIdentity", id, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("ONCHAIN_IDENTITY_DEPLOYED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateRevertsResolvedAddressToPending() {
        OnchainIdentity identity = new OnchainIdentity();
        identity.setIdentityAddress("0xrealaddress");
        when(repository.findById(id)).thenReturn(Optional.of(identity));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(identity);
        assertThat(identity.getIdentityAddress()).startsWith("0x-PENDING-ONCHAINID-");
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void alreadyPendingIdentityIsNotApplicable() {
        OnchainIdentity identity = new OnchainIdentity();
        identity.setIdentityAddress("0x-PENDING-ONCHAINID-" + UUID.randomUUID());
        when(repository.findById(id)).thenReturn(Optional.of(identity));

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
