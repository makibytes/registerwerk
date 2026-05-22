package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.internal.AssetDeploymentService;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentPort;
import de.makibytes.registerwerk.erc3643.api.Erc3643DeploymentPort;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetDeploymentService unit tests")
class AssetDeploymentServiceTest {

    @Mock
    private AssetDeploymentRepository assetDeploymentRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TokenDeploymentPort tokenDeploymentPort;

    @Mock
    private Erc3643DeploymentPort erc3643DeploymentPort;

    @Mock
    private BlockchainClientRegistry blockchainClientRegistry;

    @Mock
    private EvmContractService evmContractService;

    @InjectMocks
    private AssetDeploymentService assetDeploymentService;

    @Test
    @DisplayName("deploy should reject confidential tokens on non-fhEVM L2 chains before persisting")
    void deploy_shouldRejectConfidentialTokensOnNewL2Chains() {
        UUID assetId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.CONF_ERC20);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> assetDeploymentService.deploy(
                assetId, Chain.ARBITRUM, Network.TESTNET, UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Confidential token deployment is not supported on ARBITRUM");

        verify(assetDeploymentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("deploy should still allow standard ERC-20 deployments on newly added L2 chains")
    void deploy_shouldAllowStandardDeploymentsOnNewL2Chains() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.ERC20);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment deployment = invocation.getArgument(0);
            deployment.setId(deploymentId);
            return deployment;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.ERC20), eq(Chain.ARBITRUM), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.ARBITRUM, Network.TESTNET, actorId);

        assertThat(result.getId()).isEqualTo(deploymentId);
        assertThat(result.getChain()).isEqualTo(Chain.ARBITRUM);
        assertThat(result.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.ERC20), eq(Chain.ARBITRUM), eq(Network.TESTNET), eq("owner-placeholder"));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("deploy should allow confidential tokens on fhEVM chains")
    void deploy_shouldAllowConfidentialTokensOnFhevmChains() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.CONF_ERC20);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment deployment = invocation.getArgument(0);
            deployment.setId(deploymentId);
            return deployment;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.CONF_ERC20), eq(Chain.FHENIX), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.FHENIX, Network.TESTNET, actorId);

        assertThat(result.getChain()).isEqualTo(Chain.FHENIX);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.CONF_ERC20), eq(Chain.FHENIX), eq(Network.TESTNET), eq("owner-placeholder"));
    }

    @Test
    @DisplayName("deploy should route SPL_2022 assets through the Token-2022 mint path")
    void deploy_shouldRouteSpl2022ThroughToken2022Path() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.SPL_2022);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment deployment = invocation.getArgument(0);
            deployment.setId(deploymentId);
            return deployment;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.SPL_2022), eq(Chain.SOLANA), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.SOLANA, Network.TESTNET, UUID.randomUUID());

        assertThat(result.getChain()).isEqualTo(Chain.SOLANA);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.SPL_2022), eq(Chain.SOLANA), eq(Network.TESTNET), eq("owner-placeholder"));
    }

    @Test
    @DisplayName("deploy should route STARKNET_ERC20 assets through TokenDeploymentPort")
    void deploy_shouldRouteStarknetErc20ThroughStarknetTokenService() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.STARKNET_ERC20);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment deployment = invocation.getArgument(0);
            deployment.setId(deploymentId);
            return deployment;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.STARKNET_ERC20), eq(Chain.STARKNET), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.STARKNET, Network.TESTNET, actorId);

        assertThat(result.getChain()).isEqualTo(Chain.STARKNET);
        assertThat(result.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.STARKNET_ERC20), eq(Chain.STARKNET), eq(Network.TESTNET), eq("owner-placeholder"));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("deploy should route STELLAR_ASSET assets through TokenDeploymentPort")
    void deploy_shouldRouteStellarAssetThroughStellarAssetService() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.STELLAR_ASSET);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment deployment = invocation.getArgument(0);
            deployment.setId(deploymentId);
            return deployment;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.STELLAR_ASSET), eq(Chain.STELLAR), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.STELLAR, Network.TESTNET, actorId);

        assertThat(result.getChain()).isEqualTo(Chain.STELLAR);
        assertThat(result.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.STELLAR_ASSET), eq(Chain.STELLAR), eq(Network.TESTNET), eq("owner-placeholder"));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("deploy should reject unsupported standard on Starknet with a clear error message")
    void deploy_shouldRejectUnsupportedStandardOnStarknet() {
        UUID assetId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.ERC20);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            dep.setId(UUID.randomUUID());
            return dep;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.ERC20), eq(Chain.STARKNET), eq(Network.TESTNET), any()))
                .thenThrow(new UnsupportedOperationException("Starknet does not support token standard: ERC20"));

        assertThatThrownBy(() -> assetDeploymentService.deploy(
                assetId, Chain.STARKNET, Network.TESTNET, UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Starknet does not support token standard");
    }

    @Test
    @DisplayName("deploy should route STARKNET_ERC3525 through TokenDeploymentPort")
    void deploy_shouldRouteStarknetErc3525ThroughCreateCairoErc3525() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.STARKNET_ERC3525);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            dep.setId(deploymentId);
            return dep;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.STARKNET_ERC3525), eq(Chain.STARKNET), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.STARKNET, Network.TESTNET, UUID.randomUUID());

        assertThat(result.getChain()).isEqualTo(Chain.STARKNET);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.STARKNET_ERC3525), eq(Chain.STARKNET), eq(Network.TESTNET), eq("owner-placeholder"));
    }

    @Test
    @DisplayName("deploy should route ERC3525 on EVM through TokenDeploymentPort")
    void deploy_shouldRouteErc3525ThroughDeploymentServiceStub() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.ERC3525);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            dep.setId(deploymentId);
            return dep;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.ERC3525), eq(Chain.ETHEREUM), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.ETHEREUM, Network.TESTNET, UUID.randomUUID());

        assertThat(result.getChain()).isEqualTo(Chain.ETHEREUM);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.ERC3525), eq(Chain.ETHEREUM), eq(Network.TESTNET), eq("owner-placeholder"));
    }

    @Test
    @DisplayName("deploy should route DAML_BOND_FIXED on Canton through TokenDeploymentPort")
    void deploy_shouldRouteDamlBondFixedThroughCantonBondOperations() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.DAML_BOND_FIXED);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            dep.setId(deploymentId);
            return dep;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.DAML_BOND_FIXED), eq(Chain.CANTON), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.CANTON, Network.TESTNET, UUID.randomUUID());

        assertThat(result.getChain()).isEqualTo(Chain.CANTON);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.DAML_BOND_FIXED), eq(Chain.CANTON), eq(Network.TESTNET), eq("owner-placeholder"));
    }

    @Test
    @DisplayName("deploy should route SPL_2022_BOND through TokenDeploymentPort with SPL_2022_BOND standard")
    void deploy_shouldRouteSpl2022BondThroughSolanaTokenServiceWithExtensions() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.SPL_2022_BOND);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            dep.setId(deploymentId);
            return dep;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.SPL_2022_BOND), eq(Chain.SOLANA), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.SOLANA, Network.TESTNET, UUID.randomUUID());

        assertThat(result).isNotNull();
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.SPL_2022_BOND), eq(Chain.SOLANA), eq(Network.TESTNET), eq("owner-placeholder"));
    }
}
