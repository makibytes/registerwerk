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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
@MockitoSettings(strictness = Strictness.LENIENT)
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

    private Erc3643LifecycleService service;
    private final UUID suiteId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
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
                org.mockito.Mockito.mock(de.makibytes.registerwerk.kyc.api.OutboundDestinationGate.class));

        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        suite.setAssetDeploymentId(deploymentId);
        suite.setComplianceAddress(COMPLIANCE);
        when(suiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));

        AssetDeployment dep = new AssetDeployment();
        dep.setId(deploymentId);
        dep.setChainConfigId(chainConfigId);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.MAINNET);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(dep));
        when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);
        EvmSigner signer = mock(EvmSigner.class);
        when(signer.address()).thenReturn(SIGNER);
        when(evmContractService.signer(any(ChainDescriptor.class))).thenReturn(signer);

        when(evmContractService.call(eq(web3j), anyString(), any(Function.class))).thenAnswer(inv -> {
            Function fn = inv.getArgument(2);
            return switch (fn.getName()) {
                case "owner" -> List.<Type>of(new Address(onChainOwner));
                case "getConfig" -> java.util.Arrays.stream(onChainConfig)
                        .<Type>mapToObj(v -> new Uint256(BigInteger.valueOf(v))).toList();
                case "isCountryBlocked" -> List.<Type>of(new Bool(true));
                default -> throw new IllegalArgumentException("unexpected call " + fn.getName());
            };
        });
        when(complianceModuleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
}
