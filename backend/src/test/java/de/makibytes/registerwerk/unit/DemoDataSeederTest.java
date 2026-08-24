package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.bootstrap.DemoDataSeeder;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicyRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import de.makibytes.registerwerk.dora.api.ThirdPartyProviderRepository;
import de.makibytes.registerwerk.dora.api.ResilienceTestRepository;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.api.KycDocumentContentRepository;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApprovalRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DemoDataSeeder unit tests")
class DemoDataSeederTest {

    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;
    @Mock private AssetHolderRepository assetHolderRepository;
    @Mock private TradeListingRepository tradeListingRepository;
    @Mock private TradeExecutionRepository tradeExecutionRepository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private RpcNodeRepository rpcNodeRepository;
    @Mock private GasSponsorshipPolicyRepository gasSponsorshipPolicyRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private IctIncidentRepository ictIncidentRepository;
    @Mock private ThirdPartyProviderRepository thirdPartyProviderRepository;
    @Mock private ResilienceTestRepository resilienceTestRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private KycDocumentRepository kycDocumentRepository;
    @Mock private KycDocumentContentRepository kycDocumentContentRepository;
    @Mock private KycJurisdictionApprovalRepository kycJurisdictionApprovalRepository;
    @Mock private HolderBlockRepository holderBlockRepository;
    @Mock private AssetBondTermsRepository assetBondTermsRepository;

    private DemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new DemoDataSeeder(
                legalEntityRepository,
                appUserRepository,
                assetRepository,
                assetDeploymentRepository,
                assetHolderRepository,
                tradeListingRepository,
                tradeExecutionRepository,
                chainConfigRepository,
                rpcNodeRepository,
                gasSponsorshipPolicyRepository,
                tokenTransferRepository,
                ictIncidentRepository,
                thirdPartyProviderRepository,
                resilienceTestRepository,
                passwordEncoder,
                kycDocumentRepository,
                kycDocumentContentRepository,
                kycJurisdictionApprovalRepository,
                holderBlockRepository,
                assetBondTermsRepository,
                "ETHEREUM_SEPOLIA",
                "http://chaincache-sepolia:8080/sepolia/rpc",
                "chaincache (anvil)",
                "http://chaincache-sepolia:8080",
                "sepolia"
        );
    }

    @Test
    @DisplayName("run syncs demo RPC nodes even when demo entities already exist")
    void run_syncsNodesWhenDemoEntitiesAlreadyExist() throws Exception {
        ChainConfig ethereum = chain("ETHEREUM_MAINNET", "https://mainnet.infura.io/v3/changeme");
        ChainConfig solana = chain("SOLANA_MAINNET", "https://old.solana.invalid");

        Map<String, ChainConfig> chains = Map.of(
                ethereum.getIdentifier(), ethereum,
                solana.getIdentifier(), solana
        );

        when(legalEntityRepository.findByEntityNumber("DEMO-MC-001"))
                .thenReturn(Optional.of(new LegalEntity()));
        when(chainConfigRepository.findByIdentifier(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(chains.get(invocation.getArgument(0))));
        when(rpcNodeRepository.findByChainConfig_Identifier("ETHEREUM_MAINNET"))
                .thenReturn(List.of());
        when(rpcNodeRepository.findByChainConfig_Identifier("SOLANA_MAINNET"))
                .thenReturn(List.of());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chainConfigRepository.save(any(ChainConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<ChainConfig> savedChains = ArgumentCaptor.forClass(ChainConfig.class);
        verify(chainConfigRepository, atLeast(2)).save(savedChains.capture());
        assertThat(savedChains.getAllValues())
                .extracting(ChainConfig::getIdentifier, ChainConfig::getRpcUrl)
                .contains(
                        tuple("ETHEREUM_MAINNET", "https://eth.llamarpc.com"),
                        tuple("SOLANA_MAINNET", "https://api.mainnet-beta.solana.com")
                );

        ArgumentCaptor<RpcNode> savedNodes = ArgumentCaptor.forClass(RpcNode.class);
        verify(rpcNodeRepository, atLeast(4)).save(savedNodes.capture());
        assertThat(savedNodes.getAllValues())
                .extracting(RpcNode::getUrl)
                .contains(
                        "https://eth.llamarpc.com",
                        "https://ethereum.publicnode.com",
                        "https://eth.drpc.org",
                        "https://api.mainnet-beta.solana.com"
                );

        verify(legalEntityRepository, never()).save(any(LegalEntity.class));
    }

    @Test
    @DisplayName("run seeds a CHAINCACHE-kind node on ETHEREUM_SEPOLIA (finalitySource itself is left "
            + "untouched — RpcNodeService.recomputeFinalitySource derives it, not the seeder)")
    void run_seedsChaincacheDemoNode() throws Exception {
        ChainConfig sepolia = chain("ETHEREUM_SEPOLIA", "http://anvil:8545");

        when(legalEntityRepository.findByEntityNumber("DEMO-MC-001")).thenReturn(Optional.of(new LegalEntity()));
        when(chainConfigRepository.findByIdentifier(anyString()))
                .thenAnswer(invocation -> "ETHEREUM_SEPOLIA".equals(invocation.getArgument(0))
                        ? Optional.of(sepolia) : Optional.empty());
        when(rpcNodeRepository.findByChainConfig_Identifier(anyString())).thenReturn(List.of());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chainConfigRepository.save(any(ChainConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<RpcNode> savedNodes = ArgumentCaptor.forClass(RpcNode.class);
        verify(rpcNodeRepository, atLeast(1)).save(savedNodes.capture());
        assertThat(savedNodes.getAllValues())
                .filteredOn(n -> n.getKind() == RpcNode.NodeKind.CHAINCACHE)
                .hasSize(1)
                .first()
                .satisfies(node -> {
                    assertThat(node.getUrl()).isEqualTo("http://chaincache-sepolia:8080/sepolia/rpc");
                    assertThat(node.getManagementUrl()).isEqualTo("http://chaincache-sepolia:8080");
                    assertThat(node.getRemoteChainKey()).isEqualTo("sepolia");
                    assertThat(node.isEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("run also seeds a second CHAINCACHE-kind node on BASE_SEPOLIA (the default-on "
            + "second demo workload — a real TAG_BASED comparison next to sepolia's DEPTH_BASED one)")
    void run_seedsSecondChaincacheDemoNodeOnBaseSepolia() throws Exception {
        ChainConfig sepolia = chain("ETHEREUM_SEPOLIA", "http://anvil:8545");
        ChainConfig base = chain("BASE_SEPOLIA", "https://sepolia.base.org");

        when(legalEntityRepository.findByEntityNumber("DEMO-MC-001")).thenReturn(Optional.of(new LegalEntity()));
        when(chainConfigRepository.findByIdentifier(anyString()))
                .thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
                    case "ETHEREUM_SEPOLIA" -> Optional.of(sepolia);
                    case "BASE_SEPOLIA" -> Optional.of(base);
                    default -> Optional.empty();
                });
        when(rpcNodeRepository.findByChainConfig_Identifier(anyString())).thenReturn(List.of());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chainConfigRepository.save(any(ChainConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<RpcNode> savedNodes = ArgumentCaptor.forClass(RpcNode.class);
        verify(rpcNodeRepository, atLeast(1)).save(savedNodes.capture());
        assertThat(savedNodes.getAllValues())
                .filteredOn(n -> n.getKind() == RpcNode.NodeKind.CHAINCACHE)
                .hasSize(2)
                .extracting(RpcNode::getUrl, RpcNode::getManagementUrl, RpcNode::getRemoteChainKey)
                .contains(
                        tuple("http://chaincache-sepolia:8080/sepolia/rpc", "http://chaincache-sepolia:8080", "sepolia"),
                        tuple("http://chaincache-base:8080/base/rpc", "http://chaincache-base:8080", "base")
                );
    }

    @Test
    @DisplayName("run does not duplicate the chaincache node when already seeded")
    void run_isIdempotentForChaincacheDemoNode() throws Exception {
        ChainConfig sepolia = chain("ETHEREUM_SEPOLIA", "http://anvil:8545");
        RpcNode existingChaincacheNode = new RpcNode();
        // A real "already seeded" row has a generated id and every field this method reconciles
        // already at its desired value — set them all so the test actually exercises "nothing to
        // change", not just an unrelated null-id/null-field mismatch against a bare new RpcNode().
        org.springframework.test.util.ReflectionTestUtils.setField(existingChaincacheNode, "id", UUID.randomUUID());
        existingChaincacheNode.setChainConfig(sepolia);
        existingChaincacheNode.setUrl("http://chaincache-sepolia:8080/sepolia/rpc");
        existingChaincacheNode.setLabel("chaincache (anvil)");
        existingChaincacheNode.setManagementUrl("http://chaincache-sepolia:8080");
        existingChaincacheNode.setRemoteChainKey("sepolia");
        existingChaincacheNode.setEnabled(true);
        existingChaincacheNode.setKind(RpcNode.NodeKind.CHAINCACHE);

        when(legalEntityRepository.findByEntityNumber("DEMO-MC-001")).thenReturn(Optional.of(new LegalEntity()));
        when(chainConfigRepository.findByIdentifier(anyString()))
                .thenAnswer(invocation -> "ETHEREUM_SEPOLIA".equals(invocation.getArgument(0))
                        ? Optional.of(sepolia) : Optional.empty());
        // Only the chaincache node exists yet — syncPublicNodes' own unrelated logic still adds
        // ETHEREUM_SEPOLIA's public demo nodes and syncs its rpcUrl on top, which is not what
        // this test is about; it isolates the chaincache-specific idempotency this method owns.
        when(rpcNodeRepository.findByChainConfig_Identifier("ETHEREUM_SEPOLIA"))
                .thenReturn(List.of(existingChaincacheNode));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chainConfigRepository.save(any(ChainConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<RpcNode> savedNodes = ArgumentCaptor.forClass(RpcNode.class);
        verify(rpcNodeRepository, atLeast(0)).save(savedNodes.capture());
        assertThat(savedNodes.getAllValues())
                .filteredOn(n -> n.getKind() == RpcNode.NodeKind.CHAINCACHE)
                .isEmpty();
    }

    @Test
    @DisplayName("run does not seed a duplicate row when the existing node's kind has drifted to "
            + "DIRECT_RPC (the exact scenario a mutable-kind idempotency key used to miss), and "
            + "does not fight RpcNodeService's ownership of kind/managementUrl/remoteChainKey")
    void run_doesNotDuplicateWhenExistingNodeKindDrifted() throws Exception {
        ChainConfig sepolia = chain("ETHEREUM_SEPOLIA", "http://anvil:8545");
        RpcNode existingNode = new RpcNode();
        org.springframework.test.util.ReflectionTestUtils.setField(existingNode, "id", UUID.randomUUID());
        existingNode.setChainConfig(sepolia);
        existingNode.setUrl("http://chaincache-sepolia:8080/sepolia/rpc");
        existingNode.setLabel("chaincache (anvil)");
        existingNode.setEnabled(true);
        // A transient redetection probe failure demoted this row before this seeder ran again —
        // the bug this test pins: the old kind-keyed lookup would no longer find this row (it's
        // DIRECT_RPC now, not CHAINCACHE) and would seed a second row for the same URL instead.
        existingNode.setKind(RpcNode.NodeKind.DIRECT_RPC);
        existingNode.setManagementUrl(null);
        existingNode.setRemoteChainKey(null);

        when(legalEntityRepository.findByEntityNumber("DEMO-MC-001")).thenReturn(Optional.of(new LegalEntity()));
        when(chainConfigRepository.findByIdentifier(anyString()))
                .thenAnswer(invocation -> "ETHEREUM_SEPOLIA".equals(invocation.getArgument(0))
                        ? Optional.of(sepolia) : Optional.empty());
        when(rpcNodeRepository.findByChainConfig_Identifier("ETHEREUM_SEPOLIA"))
                .thenReturn(List.of(existingNode));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chainConfigRepository.save(any(ChainConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seeder.run(new DefaultApplicationArguments());

        // No second row for the same URL is ever saved — the actual regression. And the seeder
        // does not reassert kind/managementUrl/remoteChainKey over RpcNodeService's redetection
        // job either: the drifted row is left exactly as it was (label/enabled already matched,
        // so this run doesn't even need to save it).
        ArgumentCaptor<RpcNode> savedNodes = ArgumentCaptor.forClass(RpcNode.class);
        verify(rpcNodeRepository, atLeast(0)).save(savedNodes.capture());
        List<RpcNode> chaincacheUrlSaves = savedNodes.getAllValues().stream()
                .filter(n -> n.getUrl().equals("http://chaincache-sepolia:8080/sepolia/rpc"))
                .toList();
        assertThat(chaincacheUrlSaves).isEmpty();
        assertThat(existingNode.getKind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(existingNode.getManagementUrl()).isNull();
        assertThat(existingNode.getRemoteChainKey()).isNull();
    }

    private ChainConfig chain(String identifier, String rpcUrl) {
        ChainConfig chain = new ChainConfig();
        chain.setId(UUID.randomUUID());
        chain.setIdentifier(identifier);
        chain.setRpcUrl(rpcUrl);
        return chain;
    }
}
