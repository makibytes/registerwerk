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

import java.math.BigDecimal;
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
@DisplayName("VaultRequestFulfillmentRevertCompensator — INVERSE_FLIP compensator for VAULT_REQUEST_RESOLVED")
class VaultRequestFulfillmentRevertCompensatorTest {

    @Mock private VaultRequestRepository vaultRequestRepository;

    private VaultRequestFulfillmentRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new VaultRequestFulfillmentRevertCompensator(vaultRequestRepository);
    }

    private VaultRequest request(VaultRequestStatus status) {
        VaultRequest request = new VaultRequest();
        ReflectionTestUtils.setField(request, "id", id);
        request.setAssetId(UUID.randomUUID());
        request.setRequestId(BigInteger.TEN);
        request.setRequestType(VaultRequestType.DEPOSIT);
        request.setRequestStatus(status);
        request.setConfirmed(true);
        request.setChainConfigId(UUID.randomUUID());
        request.setBlockNumber(123L);
        return request;
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "blockchain", "VAULT_REQUEST_RESOLVED", "VaultRequest", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("VAULT_REQUEST_RESOLVED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void revertsFulfilledRequestToPending() {
        VaultRequest request = request(VaultRequestStatus.FULFILLED);
        request.setFulfilledTx("0xfulfiltx");
        request.setFulfilledAt(Instant.now());
        request.setNavAtFulfill(new BigDecimal("1.5"));
        when(vaultRequestRepository.findById(id)).thenReturn(Optional.of(request));

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(request.getFulfilledTx()).isNull();
        assertThat(request.getFulfilledAt()).isNull();
        assertThat(request.getNavAtFulfill()).isNull();
        assertThat(request.isConfirmed()).isFalse();
        assertThat(request.getChainConfigId()).isNull();
        assertThat(request.getBlockNumber()).isNull();
        verify(vaultRequestRepository).save(request);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void revertsCancelledRequestToPending() {
        VaultRequest request = request(VaultRequestStatus.CANCELLED);
        request.setCancelledTx("0xcanceltx");
        when(vaultRequestRepository.findById(id)).thenReturn(Optional.of(request));

        compensator.compensate(effect());

        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(request.getCancelledTx()).isNull();
        verify(vaultRequestRepository).save(request);
    }

    @Test
    void alreadyPendingRequestIsNotApplicable() {
        VaultRequest request = request(VaultRequestStatus.PENDING);
        when(vaultRequestRepository.findById(id)).thenReturn(Optional.of(request));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(vaultRequestRepository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void missingRequestIsNotApplicable() {
        when(vaultRequestRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
