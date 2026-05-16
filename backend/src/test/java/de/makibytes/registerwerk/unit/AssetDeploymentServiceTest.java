package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.application.asset.AssetDeploymentService;
import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.blockchain.BlockchainClientRegistry;
import de.makibytes.registerwerk.application.blockchain.ConfidentialErc20Service;
import de.makibytes.registerwerk.application.blockchain.ConfidentialErc3643Service;
import de.makibytes.registerwerk.application.blockchain.Erc1155DeploymentService;
import de.makibytes.registerwerk.application.blockchain.Erc20DeploymentService;
import de.makibytes.registerwerk.application.blockchain.Erc3643DeploymentService;
import de.makibytes.registerwerk.application.blockchain.Erc721DeploymentService;
import de.makibytes.registerwerk.application.blockchain.EvmContractService;
import de.makibytes.registerwerk.application.blockchain.SolanaTokenService;
import de.makibytes.registerwerk.application.blockchain.StarknetTokenService;
import de.makibytes.registerwerk.application.blockchain.StellarAssetService;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetDeploymentService unit tests")
class AssetDeploymentServiceTest {

    @Mock
    private AssetDeploymentRepository assetDeploymentRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private Erc20DeploymentService erc20DeploymentService;

    @Mock
    private Erc721DeploymentService erc721DeploymentService;

    @Mock
    private Erc1155DeploymentService erc1155DeploymentService;

    @Mock
    private Erc3643DeploymentService erc3643DeploymentService;

    @Mock
    private ConfidentialErc20Service confidentialErc20Service;

    @Mock
    private ConfidentialErc3643Service confidentialErc3643Service;

    @Mock
    private SolanaTokenService solanaTokenService;

    @Mock
    private StarknetTokenService starknetTokenService;

    @Mock
    private StellarAssetService stellarAssetService;

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
        verifyNoInteractions(confidentialErc20Service, auditEventPublisher);
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
        when(erc20DeploymentService.deploy(eq(assetId), any(), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.ARBITRUM, Network.TESTNET, actorId);

        assertThat(result.getId()).isEqualTo(deploymentId);
        assertThat(result.getChain()).isEqualTo(Chain.ARBITRUM);
        assertThat(result.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        verify(erc20DeploymentService).deploy(
                eq(assetId),
                any(),
                eq("owner-placeholder"));
        verify(auditEventPublisher).publish(
                eq("ASSET_DEPLOYMENT_INITIATED"),
                eq("AssetDeployment"),
                eq(deploymentId),
                eq(actorId),
                eq(null),
                any());
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
        when(confidentialErc20Service.deploy(eq(assetId), any(), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.FHENIX, Network.TESTNET, actorId);

        assertThat(result.getChain()).isEqualTo(Chain.FHENIX);
        verify(confidentialErc20Service).deploy(eq(assetId), any(), eq("owner-placeholder"));
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
        when(solanaTokenService.createSplToken2022(eq(assetId), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.SOLANA, Network.TESTNET, UUID.randomUUID());

        assertThat(result.getChain()).isEqualTo(Chain.SOLANA);
        verify(solanaTokenService).createSplToken2022(eq(assetId), eq(Network.TESTNET), eq("owner-placeholder"));
    }

    @Test
    @DisplayName("deploy should route STARKNET_ERC20 assets through StarknetTokenService")
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
        when(starknetTokenService.createCairoErc20(eq(assetId), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.STARKNET, Network.TESTNET, actorId);

        assertThat(result.getChain()).isEqualTo(Chain.STARKNET);
        assertThat(result.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        verify(starknetTokenService).createCairoErc20(
                eq(assetId), eq(Network.TESTNET), eq("owner-placeholder"));
        verify(auditEventPublisher).publish(
                eq("ASSET_DEPLOYMENT_INITIATED"),
                eq("AssetDeployment"),
                eq(deploymentId),
                eq(actorId),
                eq(null),
                any());
    }

    @Test
    @DisplayName("deploy should route STELLAR_ASSET assets through StellarAssetService")
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
        when(stellarAssetService.createStellarAsset(eq(assetId), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.STELLAR, Network.TESTNET, actorId);

        assertThat(result.getChain()).isEqualTo(Chain.STELLAR);
        assertThat(result.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        verify(stellarAssetService).createStellarAsset(
                eq(assetId), eq(Network.TESTNET), eq("owner-placeholder"));
        verify(auditEventPublisher).publish(
                eq("ASSET_DEPLOYMENT_INITIATED"),
                eq("AssetDeployment"),
                eq(deploymentId),
                eq(actorId),
                eq(null),
                any());
    }

    @Test
    @DisplayName("deploy should reject unsupported standard on Starknet with a clear error message")
    void deploy_shouldRejectUnsupportedStandardOnStarknet() {
        UUID assetId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        // ERC20 cannot be deployed on Starknet (Starknet only supports STARKNET_ERC20)
        asset.setTokenStandard(TokenStandard.ERC20);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            dep.setId(UUID.randomUUID());
            return dep;
        });

        assertThatThrownBy(() -> assetDeploymentService.deploy(
                assetId, Chain.STARKNET, Network.TESTNET, UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("STARKNET_ERC20");

        verify(starknetTokenService, never()).createCairoErc20(any(), any(), any());
    }
}
