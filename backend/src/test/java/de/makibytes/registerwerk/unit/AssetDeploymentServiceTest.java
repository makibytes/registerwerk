package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.internal.AssetDeploymentService;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentPort;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.Arrays;
import java.util.List;
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
    @DisplayName("deploy should allow confidential tokens on real Zama-coprocessor chains (Ethereum, Base)")
    void deploy_shouldAllowConfidentialTokensOnFhevmChains() {
        // FHEVM_CHAINS previously listed FHENIX/INCO — separate, non-Zama FHE stacks that
        // Registerwerk's ConfidentialERC20/ERC3643 (built against Zama's TFHE.sol) cannot
        // actually run on. Ethereum/Base are Zama's real, documented coprocessor chains.
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
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.CONF_ERC20), eq(Chain.ETHEREUM), eq(Network.TESTNET), any()))
                .thenReturn(new CompletableFuture<>());

        AssetDeployment result = assetDeploymentService.deploy(
                assetId, Chain.ETHEREUM, Network.TESTNET, actorId);

        assertThat(result.getChain()).isEqualTo(Chain.ETHEREUM);
        verify(tokenDeploymentPort).deploy(eq(assetId), eq(TokenStandard.CONF_ERC20), eq(Chain.ETHEREUM), eq(Network.TESTNET), eq("owner-placeholder"));
    }

    @Test
    @DisplayName("deploy should reject confidential tokens on Fhenix — a separate, non-Zama FHE stack")
    void deploy_shouldRejectConfidentialTokensOnFhenix() {
        UUID assetId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.CONF_ERC20);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> assetDeploymentService.deploy(assetId, Chain.FHENIX, Network.TESTNET, UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
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

    // ── Regression coverage: syncFromChain/deploy no longer key confirmation on
    // ── receipt.getContractAddress() (always null for factory/CREATE2-pattern deployments) ──

    @Test
    @DisplayName("deploy should mark CONFIRMED immediately and publish an audit event when the " +
            "deployment port already resolves a real contract address")
    void deploy_whenCompleteMarksConfirmed_whenAddressIsPresent() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.ERC20);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        AssetDeployment[] holder = new AssetDeployment[1];
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            if (dep.getId() == null) {
                dep.setId(deploymentId);
            }
            holder[0] = dep;
            return dep;
        });
        when(assetDeploymentRepository.findById(deploymentId))
                .thenAnswer(invocation -> Optional.ofNullable(holder[0]));
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.ERC20), eq(Chain.ETHEREUM), eq(Network.TESTNET), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new TokenDeploymentResult("0xtxhash", "0xdeadbeef00000000000000000000000000000001")));

        assetDeploymentService.deploy(assetId, Chain.ETHEREUM, Network.TESTNET, actorId);

        assertThat(holder[0].getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(holder[0].getContractAddress()).isEqualTo("0xdeadbeef00000000000000000000000000000001");
        assertThat(holder[0].getDeployedAt()).isNotNull();

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).anySatisfy(e -> assertThat(e).isInstanceOf(DeploymentConfirmedEvent.class));
    }

    @Test
    @DisplayName("deploy should mark FAILED and publish an audit event when the deployment tx errors")
    void deploy_whenCompleteMarksFailed_onException() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.ERC20);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        AssetDeployment[] holder = new AssetDeployment[1];
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            if (dep.getId() == null) {
                dep.setId(deploymentId);
            }
            holder[0] = dep;
            return dep;
        });
        when(assetDeploymentRepository.findById(deploymentId))
                .thenAnswer(invocation -> Optional.ofNullable(holder[0]));
        CompletableFuture<TokenDeploymentResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.ERC20), eq(Chain.ETHEREUM), eq(Network.TESTNET), any()))
                .thenReturn(failed);

        assetDeploymentService.deploy(assetId, Chain.ETHEREUM, Network.TESTNET, UUID.randomUUID());

        assertThat(holder[0].getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.FAILED);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).anySatisfy(e -> assertThat(e).isInstanceOf(DeploymentFailedEvent.class));
    }

    @Test
    @DisplayName("syncFromChain should mark FAILED on a reverted receipt, not merely a missing contractAddress")
    void syncFromChain_marksFailed_whenReceiptReverted() throws java.io.IOException {
        UUID deploymentId = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setDeployedByTx("0xtxhash");
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        Web3j web3j = org.mockito.Mockito.mock(Web3j.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x0"); // reverted
        when(web3j.ethGetTransactionReceipt("0xtxhash").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));

        assetDeploymentService.syncFromChain(deploymentId);

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.FAILED);
        verify(eventPublisher).publishEvent(any(DeploymentFailedEvent.class));
    }

    @Test
    @DisplayName("syncFromChain should mark CONFIRMED and extract the token address from event logs " +
            "when the receipt succeeded but has no top-level contractAddress (factory-pattern deploy)")
    void syncFromChain_marksConfirmed_extractingAddressFromEventLogs() throws java.io.IOException {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setTokenStandard(TokenStandard.ERC20);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(assetId);
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setDeployedByTx("0xtxhash");
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        Web3j web3j = org.mockito.Mockito.mock(Web3j.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1"); // success — but no top-level contractAddress, since `to` was the factory
        String topicSig = "0x" + org.web3j.crypto.Hash.sha3String("TokenDeployed(bytes32,uint8,address)");
        String tokenAddress = "0x" + "ab".repeat(20); // 20-byte address, 40 hex chars
        String tokenAddressTopic = "0x" + "0".repeat(24) + tokenAddress.substring(2); // left-padded to 32 bytes
        Log log = new Log();
        log.setTopics(Arrays.asList(
                topicSig,
                "0x" + "1".repeat(64), // assetId (topics[1]) — value irrelevant to extraction
                "0x" + "0".repeat(64), // tokenType (topics[2]) — value irrelevant to extraction
                tokenAddressTopic      // tokenAddress (topics[3])
        ));
        receipt.setLogs(List.of(log));
        when(web3j.ethGetTransactionReceipt("0xtxhash").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));

        assetDeploymentService.syncFromChain(deploymentId);

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(deployment.getContractAddress()).isEqualTo(tokenAddress);
        verify(eventPublisher).publishEvent(any(DeploymentConfirmedEvent.class));
    }
}
