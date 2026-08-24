package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Function;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Erc7540AdminServiceTest {

    @Mock AssetDeploymentRepository deploymentRepository;
    @Mock VaultRequestRepository requestRepository;
    @Mock DurableEvmTransactionGateway evmTransactions;
    @Mock BlockchainTransactionService txService;
    @Mock ApplicationEventPublisher events;

    private Erc7540AdminService service;
    private AssetDeployment deployment;
    private UUID deploymentId;

    @BeforeEach
    void setUp() {
        service = new Erc7540AdminService(deploymentRepository, requestRepository,
                evmTransactions, txService, events);
        deploymentId = UUID.randomUUID();
        deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(UUID.randomUUID());
        deployment.setChainConfigId(UUID.randomUUID());
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setContractAddress("0x0000000000000000000000000000000000000001");
    }

    @Test
    void genericFulfillSubmitsTxButDoesNotFlipStatusUntilConfirmed() {
        BigInteger requestId = BigInteger.TEN;
        VaultRequest request = request(requestId, VaultRequestType.REDEEM, VaultRequestStatus.PENDING);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestId(deployment.getAssetId(), requestId))
                .thenReturn(Optional.of(request));
        when(evmTransactions.submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), any(Function.class), any()))
                .thenReturn("0xtx");
        when(txService.record(eq("0xtx"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        service.fulfillRequest(deploymentId, requestId, new BigDecimal("1.23"),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<Function> function = ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), function.capture(), any());
        assertThat(function.getValue().getName()).isEqualTo("fulfillRedeemRequest");
        // submit() returns before any receipt exists — status must stay PENDING until
        // VaultConfirmationListener confirms fulfilledTx (see VaultConfirmationListenerTest).
        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(request.getFulfilledTx()).isEqualTo("0xtx");
        assertThat(request.getFulfilledAt()).isNull();
        assertThat(request.isConfirmed()).isFalse();
        verify(requestRepository).save(request);
    }

    @Test
    void alreadyCompletedRequestIsRejectedBeforeOnChainSubmission() {
        BigInteger requestId = BigInteger.ONE;
        VaultRequest request = request(requestId, VaultRequestType.DEPOSIT, VaultRequestStatus.FULFILLED);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestId(deployment.getAssetId(), requestId))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.cancelRequest(
                deploymentId, requestId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already FULFILLED");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void requestWithUnconfirmedFulfilledTxCannotBeResubmitted() {
        // requestStatus stays PENDING while a submitted tx awaits confirmation (see
        // genericFulfillSubmitsTxButDoesNotFlipStatusUntilConfirmed) — requirePendingRequest must
        // still reject a second submission attempt on the same request.
        BigInteger requestId = BigInteger.valueOf(7);
        VaultRequest request = request(requestId, VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        request.setFulfilledTx("0xalready-submitted");
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestId(deployment.getAssetId(), requestId))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.fulfillRequest(
                deploymentId, requestId, new BigDecimal("1.0"), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting confirmation");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void cancelSubmitsTxButDoesNotFlipStatusUntilConfirmed() {
        BigInteger requestId = BigInteger.valueOf(3);
        VaultRequest request = request(requestId, VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestId(deployment.getAssetId(), requestId))
                .thenReturn(Optional.of(request));
        when(evmTransactions.submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), any(Function.class), any()))
                .thenReturn("0xcanceltx");
        when(txService.record(eq("0xcanceltx"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        service.cancelRequest(deploymentId, requestId, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(request.getCancelledTx()).isEqualTo("0xcanceltx");
        assertThat(request.isConfirmed()).isFalse();
        verify(requestRepository).save(request);
    }

    private VaultRequest request(BigInteger requestId, VaultRequestType type, VaultRequestStatus status) {
        VaultRequest request = new VaultRequest();
        request.setAssetId(deployment.getAssetId());
        request.setRequestId(requestId);
        request.setRequestType(type);
        request.setRequestStatus(status);
        return request;
    }
}
