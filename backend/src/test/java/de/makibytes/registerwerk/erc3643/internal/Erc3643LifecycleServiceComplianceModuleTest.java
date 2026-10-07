package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModuleRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuerRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1-15: the backend used to call {@code setMaxInvestors(uint256)} etc. on
 * {@code EwpgComplianceModule}, whose setters are {@code (address compliance, …)} — selectors that
 * do not exist on the module, so no configured limit ever reached the chain, and the module was
 * left bound with no limits after the operator saw an error.
 */
@ExtendWith(MockitoExtension.class)
class Erc3643LifecycleServiceComplianceModuleTest {

    private static final String COMPLIANCE = "0x00000000000000000000000000000000000000c0";
    private static final String MODULE = "0x00000000000000000000000000000000000000d0";
    private static final String SIGNER = "0x00000000000000000000000000000000000000e0";

    @Mock private Erc3643SuiteRepository suiteRepository;
    @Mock private Erc3643ComplianceModuleRepository complianceModuleRepository;
    @Mock private Erc3643TrustedIssuerRepository trustedIssuerRepository;
    @Mock private Erc3643ClaimTopicRepository claimTopicRepository;
    @Mock private Erc3643IdentityRegistryRepository identityRegistryRepository;
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EvmContractService evmContractService;
    @Mock private DurableEvmTransactionGateway evmTransactions;
    @Mock private BlockchainClientRegistry blockchainClientRegistry;
    @Mock private BlockchainTransactionService txService;
    @Mock private HolderBlockGate holderBlockGate;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetHolderRepository holderRepository;

    private Erc3643LifecycleService service;
    private final UUID suiteId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final Web3j web3j = mock(Web3j.class);

    /** Values the mocked module returns from getConfig — (maxInvestors, maxBalance, cooldown, count, investors). */
    private long[] onChainConfig = {0, 0, 0, 0, 0};
    private String onChainOwner = SIGNER;

    @BeforeEach
    void setUp() {
        service = new Erc3643LifecycleService(suiteRepository, complianceModuleRepository,
                trustedIssuerRepository, claimTopicRepository, identityRegistryRepository,
                deploymentRepository, eventPublisher, evmContractService, evmTransactions,
                blockchainClientRegistry, txService, holderBlockGate,
                org.mockito.Mockito.mock(de.makibytes.registerwerk.deployment.api.AssetLookupPort.class),
                org.mockito.Mockito.mock(de.makibytes.registerwerk.kyc.api.OutboundDestinationGate.class),
                org.mockito.Mockito.mock(de.makibytes.registerwerk.travelrule.api.TravelRuleGate.class),
                holderRepository);

        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        suite.setAssetDeploymentId(deploymentId);
        suite.setComplianceAddress(COMPLIANCE);
        org.mockito.Mockito.lenient().when(suiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));

        AssetDeployment dep = new AssetDeployment();
        dep.setId(deploymentId);
        dep.setChainConfigId(chainConfigId);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.MAINNET);
        dep.setAssetId(assetId);
        org.mockito.Mockito.lenient().when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(dep));
        org.mockito.Mockito.lenient().when(holderRepository.findActiveByAssetId(eq(assetId), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.lenient().when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);
        EvmSigner signer = mock(EvmSigner.class);
        org.mockito.Mockito.lenient().when(signer.address()).thenReturn(SIGNER);
        org.mockito.Mockito.lenient().when(evmContractService.signer(any(ChainDescriptor.class))).thenReturn(signer);

        org.mockito.Mockito.lenient().when(evmContractService.call(eq(web3j), anyString(), any(Function.class))).thenAnswer(inv -> {
            Function fn = inv.getArgument(2);
            return switch (fn.getName()) {
                case "owner" -> List.<Type>of(new Address(onChainOwner));
                case "getConfig" -> java.util.Arrays.stream(onChainConfig)
                        .<Type>mapToObj(v -> new Uint256(BigInteger.valueOf(v))).toList();
                case "isCountryBlocked" -> List.<Type>of(new Bool(true));
                default -> throw new IllegalArgumentException("unexpected call " + fn.getName());
            };
        });
        org.mockito.Mockito.lenient().when(complianceModuleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static String methodId(String signature) {
        return org.web3j.crypto.Hash.sha3String(signature).substring(0, 10);
    }

    private static String selector(Function fn) {
        return FunctionEncoder.encode(fn).substring(0, 10);
    }

    private List<Function> sentFunctions(List<String> targets) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(evmContractService, org.mockito.Mockito.atLeastOnce())
                .send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), to.capture(), fn.capture());
        targets.addAll(to.getAllValues());
        return fn.getAllValues();
    }

    @Test
    @DisplayName("module setters are encoded with the (address compliance, …) signatures the contract exposes")
    void configCalls_useCompliancePrefixedSignatures() {
        List<Function> calls = Erc3643LifecycleService.complianceModuleConfigCalls(COMPLIANCE, Map.of(
                "maxInvestors", 150, "maxBalance", "1000", "transferCooldown", 60,
                "blockedCountries", List.of(840, 408)));

        assertThat(calls).extracting(Erc3643LifecycleServiceComplianceModuleTest::selector).containsExactly(
                methodId("setMaxInvestors(address,uint256)"),
                methodId("setMaxBalance(address,uint256)"),
                methodId("setTransferCooldown(address,uint256)"),
                methodId("blockCountry(address,uint16)"),
                methodId("blockCountry(address,uint16)"));
        assertThat(calls).allSatisfy(fn ->
                assertThat(((Address) fn.getInputParameters().get(0)).getValue()).isEqualTo(COMPLIANCE));
    }

    @Test
    @DisplayName("addComplianceModule binds, configures from the compliance owner and persists once the read-back matches")
    void addComplianceModule_bindsConfiguresAndVerifies() {
        onChainConfig = new long[] {150, 0, 0, 1, 0};

        service.addComplianceModule(suiteId, MODULE, "EWPG",
                Map.of("maxInvestors", 150, "blockedCountries", List.of(840)), UUID.randomUUID(), "REGISTRY_ADMIN");

        List<String> targets = new java.util.ArrayList<>();
        List<Function> sent = sentFunctions(targets);
        assertThat(sent).extracting(Function::getName)
                .containsExactly("addModule", "setMaxInvestors", "blockCountry");
        assertThat(targets).containsExactly(COMPLIANCE, MODULE, MODULE);
        verify(complianceModuleRepository).save(any());
    }

    @Test
    @DisplayName("a still-pending compliance ownership is accepted before the module is bound")
    void addComplianceModule_acceptsPendingOwnershipFirst() {
        onChainOwner = "0x00000000000000000000000000000000000000f1"; // the TREXFactory
        onChainConfig = new long[] {150, 0, 0, 0, 0};

        service.addComplianceModule(suiteId, MODULE, "EWPG", Map.of("maxInvestors", 150),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        List<Function> sent = sentFunctions(new java.util.ArrayList<>());
        assertThat(sent).extracting(Function::getName)
                .containsExactly("acceptOwnership", "addModule", "setMaxInvestors");
    }

    @Test
    @DisplayName("a read-back mismatch unbinds the module again, surfaces the error and persists nothing")
    void addComplianceModule_readBackMismatch_unbindsAndThrows() {
        onChainConfig = new long[] {0, 0, 0, 0, 0}; // limit did not land

        assertThatThrownBy(() -> service.addComplianceModule(suiteId, MODULE, "EWPG",
                Map.of("maxInvestors", 150), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxInvestors");

        List<Function> sent = sentFunctions(new java.util.ArrayList<>());
        assertThat(sent).extracting(Function::getName)
                .containsExactly("addModule", "setMaxInvestors", "removeModule");
        verify(complianceModuleRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── H4: holders that exist before the module is bound ─────────────────────

    private void holders(int count) {
        List<de.makibytes.registerwerk.deployment.api.AssetHolder> all = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            de.makibytes.registerwerk.deployment.api.AssetHolder h = new de.makibytes.registerwerk.deployment.api.AssetHolder();
            h.setAssetId(assetId);
            h.setWalletAddress(String.format("0x%040x", i + 1));
            all.add(h);
        }
        when(holderRepository.findActiveByAssetId(eq(assetId), any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> {
                    org.springframework.data.domain.Pageable p = inv.getArgument(1);
                    int from = (int) Math.min(p.getOffset(), all.size());
                    int to = Math.min(from + p.getPageSize(), all.size());
                    return new org.springframework.data.domain.PageImpl<>(all.subList(from, to), p, all.size());
                });
    }

    @Test
    @DisplayName("H4: a module bound to a token that already has holders is back-filled with syncHolders, in pages, after it is configured")
    void addComplianceModule_syncsExistingHoldersInPagesAfterConfiguring() {
        onChainConfig = new long[] {150, 0, 0, 0, 0};
        holders(250);

        service.addComplianceModule(suiteId, MODULE, "EWPG", Map.of("maxInvestors", 150),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        List<String> targets = new java.util.ArrayList<>();
        List<Function> sent = sentFunctions(targets);
        assertThat(sent).extracting(Function::getName)
                .containsExactly("addModule", "setMaxInvestors", "syncHolders", "syncHolders", "syncHolders");
        assertThat(targets).containsExactly(COMPLIANCE, MODULE, MODULE, MODULE, MODULE);
        List<Integer> pageSizes = sent.stream().filter(f -> f.getName().equals("syncHolders"))
                .map(f -> ((org.web3j.abi.datatypes.DynamicArray<?>) f.getInputParameters().get(1)).getValue().size())
                .toList();
        assertThat(pageSizes).containsExactly(100, 100, 50);
        assertThat(sent.stream().filter(f -> f.getName().equals("syncHolders")).toList()).allSatisfy(f ->
                assertThat(((Address) f.getInputParameters().get(0)).getValue()).isEqualTo(COMPLIANCE));
        verify(complianceModuleRepository).save(any());
    }

    @Test
    @DisplayName("H4: a token without holders needs no syncHolders transaction")
    void addComplianceModule_noHolders_noSync() {
        onChainConfig = new long[] {150, 0, 0, 0, 0};

        service.addComplianceModule(suiteId, MODULE, "EWPG", Map.of("maxInvestors", 150),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(sentFunctions(new java.util.ArrayList<>())).extracting(Function::getName)
                .containsExactly("addModule", "setMaxInvestors");
    }

    @Test
    @DisplayName("H4: a wallet is synced once and a non-EVM register wallet is skipped, not sent")
    void addComplianceModule_syncDeduplicatesAndSkipsInvalidWallets() {
        onChainConfig = new long[] {150, 0, 0, 0, 0};
        de.makibytes.registerwerk.deployment.api.AssetHolder a = new de.makibytes.registerwerk.deployment.api.AssetHolder();
        a.setAssetId(assetId);
        a.setWalletAddress("0x" + "ab".repeat(20));
        de.makibytes.registerwerk.deployment.api.AssetHolder dup = new de.makibytes.registerwerk.deployment.api.AssetHolder();
        dup.setAssetId(assetId);
        dup.setWalletAddress("0x" + "AB".repeat(20));
        de.makibytes.registerwerk.deployment.api.AssetHolder starknet = new de.makibytes.registerwerk.deployment.api.AssetHolder();
        starknet.setAssetId(assetId);
        starknet.setWalletAddress("0x" + "1".repeat(63));
        when(holderRepository.findActiveByAssetId(eq(assetId), any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(
                        List.of(a, dup, starknet), inv.getArgument(1), 3));

        service.addComplianceModule(suiteId, MODULE, "EWPG", Map.of("maxInvestors", 150),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        List<Function> sync = sentFunctions(new java.util.ArrayList<>()).stream()
                .filter(f -> f.getName().equals("syncHolders")).toList();
        assertThat(sync).hasSize(1);
        assertThat(((org.web3j.abi.datatypes.DynamicArray<?>) sync.get(0).getInputParameters().get(1)).getValue())
                .hasSize(1);
    }

    @Test
    @DisplayName("H4: a failing syncHolders unbinds the module again, surfaces the error and persists nothing")
    void addComplianceModule_syncFailure_unbindsAndThrows() {
        onChainConfig = new long[] {150, 0, 0, 0, 0};
        holders(3);
        org.mockito.Mockito.doAnswer(inv -> {
            Function fn = inv.getArgument(4);
            if (fn.getName().equals("syncHolders")) {
                throw new IllegalStateException("out of gas");
            }
            return null;
        }).when(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), anyString(), any(Function.class));

        assertThatThrownBy(() -> service.addComplianceModule(suiteId, MODULE, "EWPG",
                Map.of("maxInvestors", 150), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of gas");

        assertThat(sentFunctions(new java.util.ArrayList<>())).extracting(Function::getName)
                .containsExactly("addModule", "setMaxInvestors", "syncHolders", "removeModule");
        verify(complianceModuleRepository, never()).save(any());
    }
}
