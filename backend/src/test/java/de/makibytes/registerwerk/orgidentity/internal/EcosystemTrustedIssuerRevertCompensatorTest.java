package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
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
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new EcosystemTrustedIssuerRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, "0xhash", "0xtxhash", null,
                "orgidentity", "TRUSTED_ISSUER_ADDED", "EcosystemTrustedIssuer", id, null, CompensationCategory.INVERSE_FLIP,
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
        issuer.setStatus(TrustedIssuerStatus.ACTIVE);
        issuer.setChainConfigId(chainConfigId);
        issuer.setAddedTx("0xtxhash");
        issuer.setAddedBlockNumber(100L);
        issuer.setAddedBlockHash("0xhash");
        when(repository.findById(id)).thenReturn(Optional.of(issuer));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(issuer);
        assertThat(issuer.getStatus()).isEqualTo(TrustedIssuerStatus.PENDING);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void nonActiveIssuerIsNotApplicable() {
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setStatus(TrustedIssuerStatus.REMOVED);
        issuer.setChainConfigId(chainConfigId);
        issuer.setAddedTx("0xtxhash");
        issuer.setAddedBlockNumber(100L);
        issuer.setAddedBlockHash("0xhash");
        issuer.setRemovedTx("0xremove");
        issuer.setRemovedBlockNumber(101L);
        issuer.setRemovedBlockHash("0xremoveblock");
        when(repository.findById(id)).thenReturn(Optional.of(issuer));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void removalThenAdditionLifoClearsAdditionButPreservesPendingRemovalIntent() {
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setStatus(TrustedIssuerStatus.REMOVED);
        issuer.setChainConfigId(chainConfigId);
        issuer.setAddedTx("0xtxhash");
        issuer.setAddedBlockNumber(100L);
        issuer.setAddedBlockHash("0xhash");
        issuer.setRemovedTx("0xremove");
        issuer.setRemovedBlockNumber(101L);
        issuer.setRemovedBlockHash("0xremoveblock");
        Instant removalRequestedAt = Instant.parse("2026-08-23T10:15:30Z");
        issuer.setRemovedAt(removalRequestedAt);
        when(repository.findById(id)).thenReturn(Optional.of(issuer));

        ChainEffectRecord removalEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xremoveblock", "0xremove", null,
                "orgidentity", TrustedIssuerRemovalRevertCompensator.EFFECT_TYPE,
                "EcosystemTrustedIssuer", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        CompensationOutcome removalOutcome =
                new TrustedIssuerRemovalRevertCompensator(repository).compensate(removalEffect);
        CompensationOutcome additionOutcome = compensator.compensate(effect());

        assertThat(removalOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(additionOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(issuer.getStatus()).isEqualTo(TrustedIssuerStatus.REMOVAL_PENDING);
        assertThat(issuer.getAddedTx()).isEqualTo("0xtxhash");
        assertThat(issuer.getAddedBlockNumber()).isNull();
        assertThat(issuer.getAddedBlockHash()).isNull();
        assertThat(issuer.getRemovedAt()).isEqualTo(removalRequestedAt);
        assertThat(issuer.getRemovedTx()).isEqualTo("0xremove");
        assertThat(issuer.getRemovedBlockNumber()).isNull();
        assertThat(issuer.getRemovedBlockHash()).isNull();
    }

    @Test
    void replacementAdditionThenPredecessorRemovalLifoKeepsBothLifecycleGenerationsPending() {
        UUID predecessorId = UUID.randomUUID();
        String issuerAddress = "0xsameissuer";

        EcosystemTrustedIssuer replacement = new EcosystemTrustedIssuer();
        replacement.setStatus(TrustedIssuerStatus.ACTIVE);
        replacement.setChainConfigId(chainConfigId);
        replacement.setIssuerAddress(issuerAddress);
        replacement.setAddedTx("0xreadd");
        replacement.setAddedBlockNumber(102L);
        replacement.setAddedBlockHash("0xreaddblock");

        EcosystemTrustedIssuer predecessor = new EcosystemTrustedIssuer();
        predecessor.setStatus(TrustedIssuerStatus.REMOVED);
        predecessor.setChainConfigId(chainConfigId);
        predecessor.setIssuerAddress(issuerAddress);
        predecessor.setRemovedAt(Instant.parse("2026-08-23T10:15:30Z"));
        predecessor.setRemovedTx("0xremove");
        predecessor.setRemovedBlockNumber(101L);
        predecessor.setRemovedBlockHash("0xremoveblock");

        when(repository.findById(id)).thenReturn(Optional.of(replacement));
        when(repository.findById(predecessorId)).thenReturn(Optional.of(predecessor));

        ChainEffectRecord replacementEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 102L, "0xreaddblock", "0xreadd", null,
                "orgidentity", EcosystemTrustedIssuerRevertCompensator.EFFECT_TYPE,
                "EcosystemTrustedIssuer", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
        ChainEffectRecord removalEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xremoveblock", "0xremove", null,
                "orgidentity", TrustedIssuerRemovalRevertCompensator.EFFECT_TYPE,
                "EcosystemTrustedIssuer", predecessorId, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        CompensationOutcome replacementOutcome = compensator.compensate(replacementEffect);
        CompensationOutcome removalOutcome =
                new TrustedIssuerRemovalRevertCompensator(repository).compensate(removalEffect);

        assertThat(replacementOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(removalOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(replacement.getStatus()).isEqualTo(TrustedIssuerStatus.PENDING);
        assertThat(predecessor.getStatus()).isEqualTo(TrustedIssuerStatus.REMOVAL_PENDING);
        assertThat(replacement.getIssuerAddress()).isEqualTo(predecessor.getIssuerAddress());
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
