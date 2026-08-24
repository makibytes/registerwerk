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
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new IdentityRegistryRegistrationRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, "0xhash", "0xtxhash", null,
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
        entry.setChainConfigId(chainConfigId);
        entry.setRegisteredByTx("0xtxhash");
        entry.setRegistrationConfirmed(true);
        entry.setRegistrationBlockNumber(100L);
        entry.setRegistrationBlockHash("0xhash");
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
    void removalThenRegistrationLifoClearsRegistrationButPreservesPendingRemovalIntent() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        entry.setWalletAddress("0xwallet");
        entry.setChainConfigId(chainConfigId);
        entry.setRegisteredByTx("0xregister");
        entry.setRegistrationConfirmed(true);
        entry.setRegistrationBlockNumber(100L);
        entry.setRegistrationBlockHash("0xregblock");
        entry.setRemovedAt(Instant.parse("2026-08-23T10:15:30Z"));
        entry.setRemovedByTx("0xremove");
        entry.setRemovalConfirmed(true);
        entry.setRemovalBlockNumber(101L);
        entry.setRemovalBlockHash("0xremoveblock");
        when(repository.findById(id)).thenReturn(Optional.of(entry));

        ChainEffectRecord removalEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xremoveblock", "0xremove", null,
                "erc3643", "ERC3643_IDENTITY_REMOVED", "Erc3643IdentityRegistry", id, null,
                CompensationCategory.INVERSE_FLIP, null, null, null, null,
                "COMPENSATING", 1, Instant.now());
        ChainEffectRecord registrationEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 100L, "0xregblock", "0xregister", null,
                "erc3643", "ERC3643_IDENTITY_REGISTERED", "Erc3643IdentityRegistry", id, null,
                CompensationCategory.INVERSE_FLIP, null, null, null, null,
                "COMPENSATING", 1, Instant.now());

        new IdentityRegistryRemovalRevertCompensator(repository).compensate(removalEffect);
        Instant pendingRemovalAt = entry.getRemovedAt();
        CompensationOutcome outcome = compensator.compensate(registrationEffect);

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(entry.isRegistrationConfirmed()).isFalse();
        assertThat(entry.getRegistrationBlockNumber()).isNull();
        assertThat(entry.getRegistrationBlockHash()).isNull();
        assertThat(entry.getRemovedAt()).isEqualTo(pendingRemovalAt);
        assertThat(entry.getRemovedByTx()).isEqualTo("0xremove");
        assertThat(entry.isRemovalConfirmed()).isFalse();
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
