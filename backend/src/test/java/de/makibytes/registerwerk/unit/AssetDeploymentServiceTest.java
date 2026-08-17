package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.internal.AssetDeploymentService;
import de.makibytes.registerwerk.asset.internal.AssetDeploymentCompletionWriter;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentPort;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.erc3643.api.Erc3643DeploymentPort;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetDeploymentService unit tests")
class AssetDeploymentServiceTest {

    private static final String STARKNET_RPC_URL = "http://localhost/starknet-rpc";

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

    @Mock
    private AssetDeploymentCompletionWriter completionWriter;

    @Mock
    private ChainConfigRepository chainConfigRepository;

    /** Real instance (not a mock), same pattern as BlockchainTransactionServiceTest — a plain
     *  POJO with getters/setters is more reliable to configure directly than to stub. */
    private BlockchainTxProperties txProperties;

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer mockServer;

    /** Constructed manually in {@link #setUp} (not {@code @InjectMocks}) since two constructor
     *  params ({@code txProperties}, {@code restClientBuilder}) are deliberately real instances,
     *  not mocks — {@code @InjectMocks}' reflective auto-construction would otherwise still run
     *  during Mockito's annotation processing (before this class's own {@code @BeforeEach}) and
     *  NPE on {@code restClientBuilder.build()} exactly as it did before this fix, since no
     *  {@code @Mock RestClient.Builder} exists for it to find. */
    private AssetDeploymentService assetDeploymentService;

    @BeforeEach
    void setUp() {
        txProperties = new BlockchainTxProperties();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        assetDeploymentService = new AssetDeploymentService(
                assetDeploymentRepository, assetRepository, eventPublisher, tokenDeploymentPort,
                erc3643DeploymentPort, blockchainClientRegistry, evmContractService, completionWriter,
                txProperties, chainConfigRepository, restClientBuilder);
    }

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
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            if (dep.getId() == null) {
                dep.setId(deploymentId);
            }
            return dep;
        });
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.ERC20), eq(Chain.ETHEREUM), eq(Network.TESTNET), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new TokenDeploymentResult("0xtxhash", "0xdeadbeef00000000000000000000000000000001")));

        assetDeploymentService.deploy(assetId, Chain.ETHEREUM, Network.TESTNET, actorId);

        verify(completionWriter).markSubmitted(eq(deploymentId), eq(actorId),
                eq(new TokenDeploymentResult("0xtxhash", "0xdeadbeef00000000000000000000000000000001")));
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
        when(assetDeploymentRepository.save(any(AssetDeployment.class))).thenAnswer(invocation -> {
            AssetDeployment dep = invocation.getArgument(0);
            if (dep.getId() == null) {
                dep.setId(deploymentId);
            }
            return dep;
        });
        CompletableFuture<TokenDeploymentResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        when(tokenDeploymentPort.deploy(eq(assetId), eq(TokenStandard.ERC20), eq(Chain.ETHEREUM), eq(Network.TESTNET), any()))
                .thenReturn(failed);

        UUID actorId = UUID.randomUUID();
        assetDeploymentService.deploy(assetId, Chain.ETHEREUM, Network.TESTNET, actorId);

        verify(completionWriter).markFailed(eq(deploymentId), eq(actorId), any(Throwable.class));
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
        receipt.setBlockNumber("0x64"); // block 100
        receipt.setBlockHash("0xblock100");
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
        // ETHEREUM's configured depth (see application.yml) is 12; block 100 seen at head 111
        // gives exactly 12 confirmations (111 - 100 + 1).
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(111));
        txProperties.setDefaultConfirmations(12);

        assetDeploymentService.syncFromChain(deploymentId);

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(deployment.getContractAddress()).isEqualTo(tokenAddress);
        assertThat(deployment.getBlockHash()).isEqualTo("0xblock100");
        assertThat(deployment.getBlockNumber()).isEqualTo(100L);
        verify(eventPublisher).publishEvent(any(DeploymentConfirmedEvent.class));
    }

    @Test
    @DisplayName("syncFromChain should stay PENDING (recording the baseline block) when mined but "
            + "below the configured confirmation depth")
    void syncFromChain_staysPending_belowConfirmationDepth() throws java.io.IOException {
        UUID deploymentId = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setDeployedByTx("0xtxhash");
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        Web3j web3j = org.mockito.Mockito.mock(Web3j.class, Answers.RETURNS_DEEP_STUBS);
        when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setBlockNumber("0x64"); // block 100
        receipt.setBlockHash("0xblock100");
        when(web3j.ethGetTransactionReceipt("0xtxhash").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));
        // Only 3 confirmations (102 - 100 + 1) — below the default 12.
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(102));
        txProperties.setDefaultConfirmations(12);

        assetDeploymentService.syncFromChain(deploymentId);

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        assertThat(deployment.getBlockHash()).isEqualTo("0xblock100");
        assertThat(deployment.getBlockNumber()).isEqualTo(100L);
        verify(eventPublisher, never()).publishEvent(any(DeploymentConfirmedEvent.class));
    }

    @Test
    @DisplayName("syncFromChain should clear the recorded block and stay PENDING when a previously-seen "
            + "block hash no longer matches — the deployment tx was reorged out")
    void syncFromChain_clearsBlockAndStaysPending_onReorgMismatch() throws java.io.IOException {
        UUID deploymentId = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setDeployedByTx("0xtxhash");
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        deployment.setBlockHash("0xblock100a"); // recorded on an earlier poll
        deployment.setBlockNumber(100L);

        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        Web3j web3j = org.mockito.Mockito.mock(Web3j.class, Answers.RETURNS_DEEP_STUBS);
        when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setBlockNumber("0x64"); // still block 100
        receipt.setBlockHash("0xblock100b"); // different hash — reorged
        when(web3j.ethGetTransactionReceipt("0xtxhash").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));

        assetDeploymentService.syncFromChain(deploymentId);

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        assertThat(deployment.getBlockHash()).isNull();
        assertThat(deployment.getBlockNumber()).isNull();
        // Never reaches confirmations()/ethBlockNumber() — the mismatch short-circuits first.
        verify(web3j, never()).ethBlockNumber();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("syncFromChain (Starknet) confirms the deployment once the tx reaches ACCEPTED_ON_L1")
    void syncFromChain_confirmsStarknetDeployment_onAcceptedOnL1() {
        UUID deploymentId = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChain(Chain.STARKNET);
        deployment.setNetwork(Network.TESTNET);
        deployment.setDeployedByTx("0xstarknettx");
        deployment.setContractAddress("0xudcaddress");
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        ChainConfig starknetChain = new ChainConfig();
        starknetChain.setRpcUrl(STARKNET_RPC_URL);
        starknetChain.setNetworkType(ChainConfig.NetworkType.TESTNET);
        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET))
                .thenReturn(List.of(starknetChain));

        mockServer.expect(requestTo(STARKNET_RPC_URL))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"finality_status\":\"ACCEPTED_ON_L1\"}}",
                        MediaType.APPLICATION_JSON));

        assetDeploymentService.syncFromChain(deploymentId);

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);
        verify(eventPublisher).publishEvent(any(DeploymentConfirmedEvent.class));
        mockServer.verify();
    }

    @Test
    @DisplayName("syncFromChain (Starknet) marks FAILED when the tx is REJECTED")
    void syncFromChain_marksStarknetDeploymentFailed_onRejected() {
        UUID deploymentId = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChain(Chain.STARKNET);
        deployment.setNetwork(Network.TESTNET);
        deployment.setDeployedByTx("0xstarknettx");
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        ChainConfig starknetChain = new ChainConfig();
        starknetChain.setRpcUrl(STARKNET_RPC_URL);
        starknetChain.setNetworkType(ChainConfig.NetworkType.TESTNET);
        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET))
                .thenReturn(List.of(starknetChain));

        mockServer.expect(requestTo(STARKNET_RPC_URL))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"finality_status\":\"REJECTED\"}}",
                        MediaType.APPLICATION_JSON));

        assetDeploymentService.syncFromChain(deploymentId);

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.FAILED);
        verify(eventPublisher).publishEvent(any(DeploymentFailedEvent.class));
        mockServer.verify();
    }

    @Test
    @DisplayName("pollPendingDeploymentConfirmations syncs every PENDING deployment with an on-chain tx, "
            + "isolating one failure from the rest")
    void pollPendingDeploymentConfirmations_syncsAllPendingDeployments() {
        UUID okDeploymentId = UUID.randomUUID();
        AssetDeployment ok = new AssetDeployment();
        ok.setId(okDeploymentId);
        ok.setChain(Chain.STARKNET);
        ok.setNetwork(Network.TESTNET);
        ok.setDeployedByTx("0xok");
        ok.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        UUID brokenDeploymentId = UUID.randomUUID();
        AssetDeployment broken = new AssetDeployment();
        broken.setId(brokenDeploymentId);
        broken.setChain(Chain.ETHEREUM);
        broken.setNetwork(Network.TESTNET);
        broken.setDeployedByTx("0xbroken");
        broken.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        when(assetDeploymentRepository.findByDeploymentStatusAndDeployedByTxIsNotNull(
                AssetDeployment.DeploymentStatus.PENDING)).thenReturn(List.of(broken, ok));
        // "broken": simulate an unexpected RPC failure for the EVM path so we can prove one
        // deployment failing doesn't stop the rest of the batch from being polled.
        when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class)))
                .thenThrow(new RuntimeException("RPC down"));

        ChainConfig starknetChain = new ChainConfig();
        starknetChain.setRpcUrl(STARKNET_RPC_URL);
        starknetChain.setNetworkType(ChainConfig.NetworkType.TESTNET);
        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET))
                .thenReturn(List.of(starknetChain));
        mockServer.expect(requestTo(STARKNET_RPC_URL))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"finality_status\":\"ACCEPTED_ON_L1\"}}",
                        MediaType.APPLICATION_JSON));

        assetDeploymentService.pollPendingDeploymentConfirmations();

        assertThat(ok.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(broken.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        mockServer.verify();
    }
}
