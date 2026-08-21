package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Erc4626AdminServiceTest {

    @Mock AssetDeploymentRepository deploymentRepository;
    @Mock AssetVaultStateRepository vaultStateRepository;
    @Mock VaultNavStrikeRepository navStrikeRepository;
    @Mock BlockchainClientRegistry clientRegistry;
    @Mock EvmContractService evmContractService;
    @Mock BlockchainTransactionService txService;
    @Mock ApplicationEventPublisher events;
    @Mock Web3j web3j;
    @Mock EvmSigner credentials;

    private Erc4626AdminService service;
    private AssetDeployment deployment;
    private UUID deploymentId;
    private UUID assetId;

    @BeforeEach
    void setUp() {
        service = new Erc4626AdminService(deploymentRepository, vaultStateRepository, navStrikeRepository,
                clientRegistry, evmContractService, txService, events);
        deploymentId = UUID.randomUUID();
        assetId = UUID.randomUUID();
        deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(assetId);
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setContractAddress("0x0000000000000000000000000000000000000001");
    }

    @Test
    void strikeNavSubmitsTxAndRecordsHistoryButDoesNotTouchAssetVaultStateUntilConfirmed() {
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of());
        when(clientRegistry.getEvmClient(any())).thenReturn(web3j);
        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class)))
                .thenReturn(credentials);
        when(evmContractService.submit(eq(web3j), eq(credentials), eq(deployment.getContractAddress()), any(Function.class)))
                .thenReturn("0xstriketx");
        when(txService.record(eq("0xstriketx"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        service.strikeNav(deploymentId, new BigDecimal("1.05"), Instant.now(), new byte[32],
                null, UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<VaultNavStrike> strikeCaptor = ArgumentCaptor.forClass(VaultNavStrike.class);
        verify(navStrikeRepository).save(strikeCaptor.capture());
        VaultNavStrike saved = strikeCaptor.getValue();
        assertThat(saved.getTxHash()).isEqualTo("0xstriketx");
        assertThat(saved.isConfirmed()).isFalse();
        assertThat(saved.getStrikeId()).isEqualTo(1L);

        // submit() returns before any receipt exists — AssetVaultState must stay untouched until
        // VaultConfirmationListener confirms the strike (see VaultConfirmationListenerTest).
        verify(vaultStateRepository, never()).save(any());
    }

    @Test
    void setDepositCapUpsertsStateAndTracksPendingValueButDoesNotApplyUntilConfirmed() {
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(vaultStateRepository.findById(assetId)).thenReturn(Optional.empty());
        when(clientRegistry.getEvmClient(any())).thenReturn(web3j);
        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class)))
                .thenReturn(credentials);
        when(evmContractService.submit(eq(web3j), eq(credentials), eq(deployment.getContractAddress()), any(Function.class)))
                .thenReturn("0xcaptx");
        when(txService.record(eq("0xcaptx"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        service.setDepositCap(deploymentId, BigInteger.valueOf(1_000_000L), UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<AssetVaultState> stateCaptor = ArgumentCaptor.forClass(AssetVaultState.class);
        verify(vaultStateRepository).save(stateCaptor.capture());
        AssetVaultState saved = stateCaptor.getValue();
        assertThat(saved.getAssetId()).isEqualTo(assetId);
        assertThat(saved.getPendingDepositCap()).isEqualByComparingTo(BigInteger.valueOf(1_000_000L));
        assertThat(saved.getDepositCapTxHash()).isEqualTo("0xcaptx");
        assertThat(saved.getDepositCap()).isNull();
    }
}
