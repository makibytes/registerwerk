package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VaultRequestIngestRevertCompensator — INVERSE_FLIP compensator for VAULT_REQUEST_INGESTED")
class VaultRequestIngestRevertCompensatorTest {

    @Mock private VaultRequestRepository vaultRequestRepository;

    private VaultRequestIngestRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new VaultRequestIngestRevertCompensator(vaultRequestRepository);
    }

    private VaultRequest ingested() {
        VaultRequest request = new VaultRequest();
        ReflectionTestUtils.setField(request, "id", id);
        request.setRequestId(BigInteger.ONE);
        request.setRequestType(VaultRequestType.DEPOSIT);
        request.setRequestStatus(VaultRequestStatus.PENDING);
        request.setRequestedTx("0xreqtx");
        request.setRequestedBlockNumber(50L);
        request.setRequestedBlockHash("0xblock50");
        return request;
    }

    private ChainEffectRecord effect(String blockHash) {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 50L, blockHash, "0xreqtx", 0,
                "blockchain", "VAULT_REQUEST_INGESTED", "VaultRequest", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void removesUntouchedRowIngestedFromRetractedBlock() {
        VaultRequest request = ingested();
        when(vaultRequestRepository.findById(id)).thenReturn(Optional.of(request));

        assertThat(compensator.compensate(effect("0xblock50"))).isInstanceOf(CompensationOutcome.Compensated.class);
        verify(vaultRequestRepository).delete(request);
    }

    @Test
    void differentIncarnationIsNotApplicable() {
        when(vaultRequestRepository.findById(id)).thenReturn(Optional.of(ingested()));

        assertThat(compensator.compensate(effect("0xother"))).isInstanceOf(CompensationOutcome.NotApplicable.class);
        verify(vaultRequestRepository, never()).delete(any());
    }

    @Test
    void rowAlreadyActedOnIsRetriedNotDeleted() {
        VaultRequest request = ingested();
        request.setFulfilledTx("0xfulfil");
        when(vaultRequestRepository.findById(id)).thenReturn(Optional.of(request));

        assertThat(compensator.compensate(effect("0xblock50"))).isInstanceOf(CompensationOutcome.Failed.class);
        verify(vaultRequestRepository, never()).delete(any());
    }
}
