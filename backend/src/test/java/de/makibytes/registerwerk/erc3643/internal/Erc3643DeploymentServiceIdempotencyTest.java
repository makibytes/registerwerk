package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.protocol.Web3j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-19: the ERC-3643 suite deploy is idempotent. A lost response (receipt wait times out) used to
 * leave an orphan suite on chain — the factory refuses a second suite per assetId — and the asset
 * undeployable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Erc3643DeploymentService — deploy idempotency (T3-19)")
class Erc3643DeploymentServiceIdempotencyTest {

    private static final String FACTORY = "0x00000000000000000000000000000000000000f1";
    private static final String INNER_FACTORY = "0x00000000000000000000000000000000000000f2";
    private static final String SIGNER = "0x00000000000000000000000000000000000000e0";
    private static final String TOKEN = "0x00000000000000000000000000000000000000a1";
    private static final String OTHER = "0x00000000000000000000000000000000000000ee";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    @Mock BlockchainClientRegistry clientRegistry;
    @Mock OnchainIdentityRepository identityRepository;
    @Mock OnchainClaimRepository claimRepository;
    @Mock Erc3643SuiteRepository suiteRepository;
    @Mock Erc3643ClaimTopicRepository claimTopicRepository;
    @Mock AssetDeploymentRepository deploymentRepository;
    @Mock AssetLookupPort assetLookupPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ExplorerUrlBuilder explorerUrlBuilder;
    @Mock ChainConfigRepository chainConfigRepository;
    @Mock EvmContractService evmContractService;
    @Mock DurableEvmTransactionGateway evmTransactions;
    @Mock ContractAddressConfig contractAddressConfig;
    @Mock ClaimSigningService claimSigningService;
    @Mock BlockchainTransactionService blockchainTransactionService;
    @Mock EvmSigner signer;

    private Erc3643DeploymentService service;
    private final Web3j web3j = mock(Web3j.class);
    private final UUID assetId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private AssetDeployment deployment;
    /** Token the factory reports under our salt; zero until "the lost tx lands". */
    private String suiteToken = ZERO;
    private String suiteOwner = INNER_FACTORY;
    private byte[] suiteAssetId;

    @BeforeEach
    void setUp() throws Exception {
        service = new Erc3643DeploymentService(clientRegistry, identityRepository, claimRepository,
                suiteRepository, claimTopicRepository, deploymentRepository, assetLookupPort,
                eventPublisher, explorerUrlBuilder, chainConfigRepository, evmContractService,
                evmTransactions, contractAddressConfig, claimSigningService, blockchainTransactionService);
        suiteAssetId = EvmUtils.uuidToBytes32(assetId);

        deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(assetId);
        deployment.setChainConfigId(chainConfigId);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        org.mockito.Mockito.lenient().when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        ChainConfig chainConfig = new ChainConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(chainConfig, "id", chainConfigId);
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        chainConfig.setNetworkType(ChainConfig.NetworkType.TESTNET);
        org.mockito.Mockito.lenient().when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        org.mockito.Mockito.lenient().when(contractAddressConfig.requireTrexFactory("ETHEREUM_SEPOLIA")).thenReturn(FACTORY);
        org.mockito.Mockito.lenient().when(contractAddressConfig.requireClaimIssuer("ETHEREUM_SEPOLIA")).thenReturn(OTHER);
        org.mockito.Mockito.lenient().when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_SEPOLIA")).thenReturn(web3j);
        org.mockito.Mockito.lenient().when(evmContractService.signer(chainConfigId)).thenReturn(signer);
        org.mockito.Mockito.lenient().when(signer.address()).thenReturn(SIGNER);
        org.mockito.Mockito.lenient().when(assetLookupPort.findById(assetId)).thenReturn(Optional.of(new AssetLookupPort.AssetInfo(
                assetId, "Green Bond", null, TokenStandard.ERC3643, null, null, null, null, "APPROVED")));
        org.mockito.Mockito.lenient().when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(suiteRepository.save(any())).thenAnswer(inv -> {
            Erc3643Suite s = inv.getArgument(0);
            if (s.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            }
            return s;
        });
        org.mockito.Mockito.lenient().when(deploymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(evmContractService.call(any(), any(), any(Function.class))).thenAnswer(inv -> {
            Function fn = inv.getArgument(2);
            List<Type> out = new ArrayList<>();
            switch (fn.getName()) {
                case "getSuiteAddresses" -> {
                    for (int i = 0; i < 6; i++) {
                        out.add(new Address(i == 0 ? suiteToken : suiteToken.equals(ZERO) ? ZERO : OTHER));
                    }
                }
                case "assetId" -> out.add(new Bytes32(suiteAssetId));
                case "isAgent" -> out.add(new Bool(true));
                case "owner" -> out.add(new Address(suiteOwner));
                case "trexFactory" -> out.add(new Address(INNER_FACTORY));
                default -> { }
            }
            return out;
        });
    }

    private ChainDescriptor descriptor() {
        return new ChainDescriptor(Chain.ETHEREUM, Network.TESTNET);
    }

    @Test
    @DisplayName("adoptsSuiteDeployedByLostTx: receipt wait fails but the suite landed -> adopted, tx hash kept, no second broadcast")
    void adoptsSuiteDeployedByLostTx() throws Exception {
        when(evmContractService.submit(eq(chainConfigId), any(), any(), eq(FACTORY), any(Function.class)))
                .thenAnswer(inv -> {
                    suiteToken = TOKEN; // the tx lands although we will not see its receipt
                    return "0xlosttx";
                });
        when(evmContractService.waitForReceipt(any(), eq("0xlosttx")))
                .thenThrow(new RuntimeException("Transaction not mined within 120s"));

        TokenDeploymentResult result = service.deploy(deploymentId, assetId, descriptor(), SIGNER).join();

        assertThat(result.txHash()).isEqualTo("0xlosttx");
        assertThat(result.contractAddress()).isEqualTo(TOKEN);
        assertThat(deployment.getDeployedByTx()).isEqualTo("0xlosttx"); // persisted before the wait
        org.mockito.ArgumentCaptor<Erc3643Suite> saved = org.mockito.ArgumentCaptor.forClass(Erc3643Suite.class);
        verify(suiteRepository).save(saved.capture());
        assertThat(saved.getValue().getAssetDeploymentId()).isEqualTo(deploymentId);
        assertThat(saved.getValue().getTokenAddress()).isEqualTo(TOKEN);
        assertThat(saved.getValue().getFactoryTxHash()).isEqualTo("0xlosttx");
    }

    @Test
    @DisplayName("a receipt that never arrives and no suite on chain leaves the row PENDING with the tx hash")
    void unminedTxStaysPendingWithHash() throws Exception {
        when(evmContractService.submit(eq(chainConfigId), any(), any(), eq(FACTORY), any(Function.class)))
                .thenReturn("0xslowtx");
        when(evmContractService.waitForReceipt(any(), eq("0xslowtx")))
                .thenThrow(new RuntimeException("Transaction not mined within 120s"));

        TokenDeploymentResult result = service.deploy(deploymentId, assetId, descriptor(), SIGNER).join();

        assertThat(result.txHash()).isEqualTo("0xslowtx");
        assertThat(result.contractAddress()).isNull();
        assertThat(deployment.getDeployedByTx()).isEqualTo("0xslowtx");
        verify(suiteRepository, never()).save(any());
    }

    @Test
    @DisplayName("lookupByDeploymentIdWithTwoRows: the deploy step resolves its row by id, never by (asset, chain)")
    void lookupByDeploymentIdWithTwoRows() throws Exception {
        AssetDeployment earlierFailed = new AssetDeployment();
        earlierFailed.setId(UUID.randomUUID());
        earlierFailed.setAssetId(assetId);
        earlierFailed.setChainConfigId(chainConfigId);
        earlierFailed.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
        when(evmContractService.submit(eq(chainConfigId), any(), any(), eq(FACTORY), any(Function.class)))
                .thenReturn("0xtx");
        when(evmContractService.waitForReceipt(any(), eq("0xtx"))).thenThrow(new RuntimeException("slow"));

        service.deploy(deploymentId, assetId, descriptor(), SIGNER).join();

        assertThat(deployment.getDeployedByTx()).isEqualTo("0xtx");
        assertThat(earlierFailed.getDeployedByTx()).isNull();
    }

    @Test
    @DisplayName("a foreign suite under our salt (wrong assetId) fails loudly instead of being adopted")
    void foreignSuiteIsNotAdopted() {
        suiteToken = TOKEN;
        suiteAssetId = new byte[32]; // not ours

        assertThatThrownBy(() -> service.deploy(deploymentId, assetId, descriptor(), SIGNER).join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ours");
        verify(suiteRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordSuiteForDeployment is a no-op when the suite row already exists")
    void recordSuiteIsIdempotent() {
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.of(new Erc3643Suite()));

        service.recordSuiteForDeployment(deploymentId);

        verify(suiteRepository, never()).save(any());
    }
}
