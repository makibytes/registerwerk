package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
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
@DisplayName("IdentityRegistryRegistrationRevertCompensator — INVERSE_FLIP compensator for ERC3643_IDENTITY_REGISTERED")
class IdentityRegistryRegistrationRevertCompensatorTest {

    @Mock private Erc3643IdentityRegistryRepository repository;

    private IdentityRegistryRegistrationRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new IdentityRegistryRegistrationRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "erc3643", "ERC3643_IDENTITY_REGISTERED", "Erc3643IdentityRegistry", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("ERC3643_IDENTITY_REGISTERED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateSoftRemovesActiveEntry() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        entry.setWalletAddress("0xwallet");
        when(repository.findById(id)).thenReturn(Optional.of(entry));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(entry);
        assertThat(entry.getRemovedAt()).isNotNull();
        assertThat(entry.isActive()).isFalse();
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void alreadyRemovedEntryIsNotApplicable() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        entry.setRemovedAt(Instant.now());
        when(repository.findById(id)).thenReturn(Optional.of(entry));

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
