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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdentityRegistryRemovalRevertCompensator — INVERSE_FLIP compensator for ERC3643_IDENTITY_REMOVED")
class IdentityRegistryRemovalRevertCompensatorTest {

    @Mock private Erc3643IdentityRegistryRepository repository;

    private IdentityRegistryRemovalRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new IdentityRegistryRemovalRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, "0xhash", "0xtxhash", null,
                "erc3643", "ERC3643_IDENTITY_REMOVED", "Erc3643IdentityRegistry", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("ERC3643_IDENTITY_REMOVED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateReturnsRemovalToPendingWithoutReactivatingEntry() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        entry.setWalletAddress("0xwallet");
        Instant removalIntentRecordedAt = Instant.parse("2026-08-23T10:15:30Z");
        entry.setRemovedAt(removalIntentRecordedAt);
        entry.setChainConfigId(chainConfigId);
        entry.setRemovedByTx("0xtxhash");
        entry.setRemovalConfirmed(true);
        entry.setRemovalBlockNumber(100L);
        entry.setRemovalBlockHash("0xhash");
        when(repository.findById(id)).thenReturn(Optional.of(entry));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(entry);
        assertThat(entry.getRemovedAt()).isEqualTo(removalIntentRecordedAt);
        assertThat(entry.isActive()).isFalse();
        assertThat(entry.getRemovedByTx()).isEqualTo("0xtxhash");
        assertThat(entry.isRemovalConfirmed()).isFalse();
        assertThat(entry.getRemovalBlockNumber()).isNull();
        assertThat(entry.getRemovalBlockHash()).isNull();
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void compensationRestoresFailClosedIntentWhenRemovedAtWasMissing() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        entry.setChainConfigId(chainConfigId);
        entry.setRemovedByTx("0xtxhash");
        entry.setRemovalConfirmed(true);
        entry.setRemovalBlockNumber(100L);
        entry.setRemovalBlockHash("0xhash");
        when(repository.findById(id)).thenReturn(Optional.of(entry));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(entry);
        assertThat(entry.getRemovedAt()).isNotNull();
        assertThat(entry.isActive()).isFalse();
        assertThat(entry.isRemovalConfirmed()).isFalse();
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void staleRemovalIncarnationCannotChangeCurrentPendingIntent() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        Instant removalIntentRecordedAt = Instant.parse("2026-08-23T10:15:30Z");
        entry.setRemovedAt(removalIntentRecordedAt);
        entry.setChainConfigId(chainConfigId);
        entry.setRemovedByTx("0xnewtx");
        entry.setRemovalConfirmed(true);
        entry.setRemovalBlockNumber(101L);
        entry.setRemovalBlockHash("0xnewhash");
        when(repository.findById(id)).thenReturn(Optional.of(entry));

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
        assertThat(entry.getRemovedAt()).isEqualTo(removalIntentRecordedAt);
        assertThat(entry.isRemovalConfirmed()).isTrue();
        assertThat(entry.getRemovalBlockHash()).isEqualTo("0xnewhash");
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
