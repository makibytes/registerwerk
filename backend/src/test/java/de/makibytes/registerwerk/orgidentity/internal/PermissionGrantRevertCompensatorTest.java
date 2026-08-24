package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
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
@DisplayName("PermissionGrantRevertCompensator — INVERSE_FLIP compensator for PERMISSION_GRANTED")
class PermissionGrantRevertCompensatorTest {

    @Mock private PermissionGrantRepository repository;

    private PermissionGrantRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new PermissionGrantRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, "0xhash", "0xtxhash", null,
                "orgidentity", "PERMISSION_GRANTED", "PermissionGrant", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("PERMISSION_GRANTED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateRevertsActiveGrant() {
        PermissionGrant grant = new PermissionGrant();
        grant.setStatus(PermissionGrantStatus.ACTIVE);
        grant.setGrantedChainConfigId(chainConfigId);
        grant.setGrantedTx("0xtxhash");
        grant.setGrantedBlockNumber(100L);
        grant.setGrantedBlockHash("0xhash");
        when(repository.findById(id)).thenReturn(Optional.of(grant));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(grant);
        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.PENDING);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void nonActiveGrantIsNotApplicable() {
        PermissionGrant grant = new PermissionGrant();
        grant.setStatus(PermissionGrantStatus.REVOKED);
        grant.setGrantedChainConfigId(chainConfigId);
        grant.setGrantedTx("0xtxhash");
        grant.setGrantedBlockNumber(100L);
        grant.setGrantedBlockHash("0xhash");
        grant.setRevokedTx("0xrevoke");
        grant.setRevokedChainConfigId(chainConfigId);
        grant.setRevokedBlockNumber(101L);
        grant.setRevokedBlockHash("0xrevokeblock");
        when(repository.findById(id)).thenReturn(Optional.of(grant));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void revocationThenGrantLifoClearsGrantButPreservesPendingRevocationIntent() {
        PermissionGrant grant = new PermissionGrant();
        grant.setStatus(PermissionGrantStatus.REVOKED);
        grant.setGrantedChainConfigId(chainConfigId);
        grant.setGrantedTx("0xtxhash");
        grant.setGrantedBlockNumber(100L);
        grant.setGrantedBlockHash("0xhash");
        grant.setRevokedTx("0xrevoke");
        grant.setRevokedChainConfigId(chainConfigId);
        grant.setRevokedBlockNumber(101L);
        grant.setRevokedBlockHash("0xrevokeblock");
        Instant revocationRequestedAt = Instant.parse("2026-08-23T10:15:30Z");
        grant.setRevokedAt(revocationRequestedAt);
        when(repository.findById(id)).thenReturn(Optional.of(grant));

        ChainEffectRecord revocationEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xrevokeblock", "0xrevoke", null,
                "orgidentity", PermissionRevocationRevertCompensator.EFFECT_TYPE,
                "PermissionGrant", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        CompensationOutcome revocationOutcome =
                new PermissionRevocationRevertCompensator(repository).compensate(revocationEffect);
        CompensationOutcome grantOutcome = compensator.compensate(effect());

        assertThat(revocationOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(grantOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.REVOCATION_PENDING);
        assertThat(grant.getGrantedTx()).isEqualTo("0xtxhash");
        assertThat(grant.getGrantedChainConfigId()).isNull();
        assertThat(grant.getGrantedBlockNumber()).isNull();
        assertThat(grant.getGrantedBlockHash()).isNull();
        assertThat(grant.getRevokedAt()).isEqualTo(revocationRequestedAt);
        assertThat(grant.getRevokedTx()).isEqualTo("0xrevoke");
        assertThat(grant.getRevokedChainConfigId()).isNull();
        assertThat(grant.getRevokedBlockNumber()).isNull();
        assertThat(grant.getRevokedBlockHash()).isNull();
    }

    @Test
    void replacementGrantThenPredecessorRevocationLifoKeepsBothLifecycleGenerationsPending() {
        UUID predecessorId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();

        PermissionGrant replacement = new PermissionGrant();
        replacement.setStatus(PermissionGrantStatus.ACTIVE);
        replacement.setPermissionDefinitionId(definitionId);
        replacement.setOrgRegistrationId(registrationId);
        replacement.setGrantType(PermissionGrantType.ORG);
        replacement.setGrantedChainConfigId(chainConfigId);
        replacement.setGrantedTx("0xregrant");
        replacement.setGrantedBlockNumber(102L);
        replacement.setGrantedBlockHash("0xregrantblock");

        PermissionGrant predecessor = new PermissionGrant();
        predecessor.setStatus(PermissionGrantStatus.REVOKED);
        predecessor.setPermissionDefinitionId(definitionId);
        predecessor.setOrgRegistrationId(registrationId);
        predecessor.setGrantType(PermissionGrantType.ORG);
        predecessor.setRevokedAt(Instant.parse("2026-08-23T10:15:30Z"));
        predecessor.setRevokedTx("0xrevoke");
        predecessor.setRevokedChainConfigId(chainConfigId);
        predecessor.setRevokedBlockNumber(101L);
        predecessor.setRevokedBlockHash("0xrevokeblock");

        when(repository.findById(id)).thenReturn(Optional.of(replacement));
        when(repository.findById(predecessorId)).thenReturn(Optional.of(predecessor));

        ChainEffectRecord replacementEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 102L, "0xregrantblock", "0xregrant", null,
                "orgidentity", PermissionGrantRevertCompensator.EFFECT_TYPE,
                "PermissionGrant", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
        ChainEffectRecord revocationEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xrevokeblock", "0xrevoke", null,
                "orgidentity", PermissionRevocationRevertCompensator.EFFECT_TYPE,
                "PermissionGrant", predecessorId, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        CompensationOutcome replacementOutcome = compensator.compensate(replacementEffect);
        CompensationOutcome revocationOutcome =
                new PermissionRevocationRevertCompensator(repository).compensate(revocationEffect);

        assertThat(replacementOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(revocationOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(replacement.getStatus()).isEqualTo(PermissionGrantStatus.PENDING);
        assertThat(predecessor.getStatus()).isEqualTo(PermissionGrantStatus.REVOCATION_PENDING);
        assertThat(replacement.getPermissionDefinitionId()).isEqualTo(predecessor.getPermissionDefinitionId());
        assertThat(replacement.getOrgRegistrationId()).isEqualTo(predecessor.getOrgRegistrationId());
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
