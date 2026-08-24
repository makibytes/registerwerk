package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
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
class PermissionRevocationRevertCompensatorTest {

    @Mock private PermissionGrantRepository repository;

    private PermissionRevocationRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new PermissionRevocationRevertCompensator(repository);
    }

    private ChainEffectRecord effect(String blockHash) {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, blockHash, "0xrevoke", null,
                "orgidentity", "PERMISSION_REVOKED", "PermissionGrant", id, null,
                CompensationCategory.INVERSE_FLIP, null, null, null, null,
                "COMPENSATING", 1, Instant.now());
    }

    private PermissionGrant confirmed(String blockHash) {
        PermissionGrant grant = new PermissionGrant();
        grant.setStatus(PermissionGrantStatus.REVOKED);
        grant.setRevokedChainConfigId(chainConfigId);
        grant.setRevokedTx("0xrevoke");
        grant.setRevokedBlockNumber(100L);
        grant.setRevokedBlockHash(blockHash);
        return grant;
    }

    @Test
    void exactIncarnationReturnsToFailClosedVerification() {
        PermissionGrant grant = confirmed("0xaaa");
        when(repository.findById(id)).thenReturn(Optional.of(grant));

        CompensationOutcome outcome = compensator.compensate(effect("0xaaa"));

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.REVOCATION_PENDING);
        assertThat(grant.getRevokedTx()).isEqualTo("0xrevoke");
        assertThat(grant.getRevokedBlockHash()).isNull();
        verify(repository).save(grant);
    }

    @Test
    void staleOldCompensationCannotUndoNewerConfirmation() {
        PermissionGrant grant = confirmed("0xbbb");
        when(repository.findById(id)).thenReturn(Optional.of(grant));

        CompensationOutcome outcome = compensator.compensate(effect("0xaaa"));

        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.REVOKED);
        verify(repository, never()).save(any());
    }
}
