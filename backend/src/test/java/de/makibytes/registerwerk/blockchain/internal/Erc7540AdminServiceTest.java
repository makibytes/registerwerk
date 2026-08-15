package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
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
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;

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
    @Mock BlockchainClientRegistry clientRegistry;
    @Mock EvmContractService evmContractService;
    @Mock BlockchainTransactionService txService;
    @Mock ApplicationEventPublisher events;
    @Mock Web3j web3j;
    @Mock EvmSigner credentials;

    private Erc7540AdminService service;
    private AssetDeployment deployment;
    private UUID deploymentId;

    @BeforeEach
    void setUp() {
        service = new Erc7540AdminService(deploymentRepository, requestRepository, clientRegistry,
                evmContractService, txService, events);
        deploymentId = UUID.randomUUID();
        deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(UUID.randomUUID());
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setContractAddress("0x0000000000000000000000000000000000000001");
    }

    @Test
    void genericFulfillDispatchesRedeemRequestToRedeemFunction() {
        BigInteger requestId = BigInteger.TEN;
        VaultRequest request = request(requestId, VaultRequestType.REDEEM, VaultRequestStatus.PENDING);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestId(deployment.getAssetId(), requestId))
                .thenReturn(Optional.of(request));
        when(clientRegistry.getEvmClient(any())).thenReturn(web3j);
        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class)))
                .thenReturn(credentials);
        when(evmContractService.submit(eq(web3j), eq(credentials), eq(deployment.getContractAddress()), any(Function.class)))
                .thenReturn("0xtx");
        when(txService.record(eq("0xtx"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        service.fulfillRequest(deploymentId, requestId, new BigDecimal("1.23"),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<Function> function = ArgumentCaptor.forClass(Function.class);
        verify(evmContractService).submit(eq(web3j), eq(credentials),
                eq(deployment.getContractAddress()), function.capture());
        assertThat(function.getValue().getName()).isEqualTo("fulfillRedeemRequest");
        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.FULFILLED);
        assertThat(request.getFulfilledAt()).isNotNull();
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
        verify(evmContractService, never()).submit(any(), any(), any(), any(Function.class));
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
