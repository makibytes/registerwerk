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
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModule;
import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModuleRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuerRepository;
import de.makibytes.registerwerk.erc3643.events.ComplianceModuleReplacedEvent;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H4: tokens that were bound to the legacy open-setter {@code EwpgComplianceModule} keep a module whose
 * setters any wallet can call and that has no holder back-fill. The backend must detect it by ABI, and the
 * swap must never leave the token without an enforcing module.
 */
@DisplayName("Legacy compliance module: detection and replacement")
class Erc3643LegacyComplianceModuleTest {

    private static final String COMPLIANCE = "0x00000000000000000000000000000000000000c0";
    private static final String LEGACY = "0x00000000000000000000000000000000000000d1";
    private static final String CURRENT = "0x00000000000000000000000000000000000000d2";
    private static final String OTHER = "0x00000000000000000000000000000000000000d3";
    private static final String SIGNER = "0x00000000000000000000000000000000000000e0";
    private static final String POOL = "0x00000000000000000000000000000000000000a1";

    private final Erc3643SuiteRepository suiteRepository = mock(Erc3643SuiteRepository.class);
    private final Erc3643ComplianceModuleRepository moduleRepository = mock(Erc3643ComplianceModuleRepository.class);
    private final AssetDeploymentRepository deploymentRepository = mock(AssetDeploymentRepository.class);
    private final AssetHolderRepository holderRepository = mock(AssetHolderRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EvmContractService evm = mock(EvmContractService.class);
    private final BlockchainClientRegistry clients = mock(BlockchainClientRegistry.class);
    private final Web3j web3j = mock(Web3j.class);
    private final UUID suiteId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private Erc3643LifecycleService service;

    private boolean newModuleAlreadyBound = false;
    private String legacyGetConfig = "revert";   // "revert" | "empty" | "rpc"
    private String legacyName = "EwpgComplianceModule";

    @BeforeEach
    void setUp() {
        service = new Erc3643LifecycleService(suiteRepository, moduleRepository,
                mock(Erc3643TrustedIssuerRepository.class), mock(Erc3643ClaimTopicRepository.class),
                mock(Erc3643IdentityRegistryRepository.class), deploymentRepository, events, evm,
                mock(DurableEvmTransactionGateway.class), clients, mock(BlockchainTransactionService.class),
                mock(HolderBlockGate.class), mock(de.makibytes.registerwerk.deployment.api.AssetLookupPort.class),
                mock(de.makibytes.registerwerk.kyc.api.OutboundDestinationGate.class),
                mock(de.makibytes.registerwerk.travelrule.api.TravelRuleGate.class), holderRepository);

        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        suite.setAssetDeploymentId(deploymentId);
        suite.setComplianceAddress(COMPLIANCE);
        when(suiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));
        AssetDeployment dep = new AssetDeployment();
        dep.setId(deploymentId);
        dep.setAssetId(assetId);
        dep.setChainConfigId(chainConfigId);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.MAINNET);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(dep));
        when(clients.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);
        EvmSigner signer = mock(EvmSigner.class);
        when(signer.address()).thenReturn(SIGNER);
        when(evm.signer(any(ChainDescriptor.class))).thenReturn(signer);

        AssetHolder investor = holder("0x00000000000000000000000000000000000000b1", HolderKind.INVESTOR);
        AssetHolder pool = holder(POOL, HolderKind.NOMINEE_POOL);
        when(holderRepository.findActiveByAssetId(eq(assetId), any(Pageable.class))).thenAnswer(inv ->
                new PageImpl<>(List.of(investor, pool), inv.getArgument(1), 2));
        when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(moduleRepository.findBySuiteIdAndRemovedAtIsNull(suiteId)).thenReturn(List.of());

        when(evm.call(eq(web3j), anyString(), any(Function.class))).thenAnswer(inv -> {
            String target = inv.getArgument(1);
            Function fn = inv.getArgument(2);
            return switch (fn.getName()) {
                case "owner" -> List.<Type>of(new Address(SIGNER));
                case "getModules" -> List.<Type>of(new DynamicArray<>(Address.class,
                        List.of(new Address(LEGACY), new Address(OTHER))));
                case "isModuleBound" -> List.<Type>of(new Bool(newModuleAlreadyBound));
                case "name" -> List.<Type>of(new Utf8String(
                        target.equals(LEGACY) || target.equals(CURRENT) ? legacyName : "CountryAllowModule"));
                case "getConfig" -> {
                    if (target.equals(LEGACY)) {
                        yield switch (legacyGetConfig) {
                            case "empty" -> List.<Type>of();
                            case "rpc" -> throw new RuntimeException("eth_call error: connection refused");
                            default -> throw new RuntimeException("eth_call error: execution reverted");
                        };
                    }
                    yield List.<Type>of(new Uint256(BigInteger.valueOf(150)), new Uint256(BigInteger.ZERO),
                            new Uint256(BigInteger.ZERO), new Uint256(BigInteger.ZERO), new Uint256(BigInteger.ZERO));
                }
                case "isNomineePool" -> List.<Type>of(new Bool(true));
                default -> throw new IllegalArgumentException("unexpected call " + fn.getName());
            };
        });
    }

    private AssetHolder holder(String wallet, HolderKind kind) {
        AssetHolder h = new AssetHolder();
        h.setId(UUID.randomUUID());
        h.setAssetId(assetId);
        h.setWalletAddress(wallet);
        h.setHolderKind(kind);
        return h;
    }

    private List<Function> sent(List<String> targets) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(evm, org.mockito.Mockito.atLeast(0))
                .send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), to.capture(), fn.capture());
        targets.addAll(to.getAllValues());
        return fn.getAllValues();
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a module that answers name() like EwpgComplianceModule but reverts getConfig is LEGACY; others are not")
    void detectsTheLegacyModuleByAbi() {
        List<Erc3643LifecycleService.BoundModule> bound = service.inspectBoundModules(suiteId);

        assertThat(bound).extracting(Erc3643LifecycleService.BoundModule::address).containsExactly(LEGACY, OTHER);
        assertThat(bound.get(0).generation()).isEqualTo(Erc3643LifecycleService.ModuleGeneration.LEGACY);
        assertThat(bound.get(1).generation()).isEqualTo(Erc3643LifecycleService.ModuleGeneration.NOT_EWPG);
    }

    @Test
    @DisplayName("an empty getConfig answer (no such function) is legacy too")
    void emptyAnswerIsLegacy() {
        legacyGetConfig = "empty";
        assertThat(service.inspectBoundModules(suiteId).get(0).generation())
                .isEqualTo(Erc3643LifecycleService.ModuleGeneration.LEGACY);
    }

    @Test
    @DisplayName("an RPC outage is UNKNOWN, never a false 'legacy' alarm")
    void rpcOutageIsUnknown() {
        legacyGetConfig = "rpc";
        assertThat(service.inspectBoundModules(suiteId).get(0).generation())
                .isEqualTo(Erc3643LifecycleService.ModuleGeneration.UNKNOWN);
    }

    // ── Replacement ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("replace binds, configures, carries the nominee pools and back-fills holders BEFORE unbinding the legacy module")
    void replaceNeverLeavesTheTokenWithoutAnEnforcingModule() {
        service.replaceLegacyComplianceModule(suiteId, LEGACY, CURRENT, "EWPG", Map.of("maxInvestors", 150),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        List<String> targets = new ArrayList<>();
        List<Function> sent = sent(targets);
        assertThat(sent).extracting(Function::getName)
                .containsExactly("addModule", "setMaxInvestors", "setNomineePool", "syncHolders", "removeModule");
        assertThat(targets).containsExactly(COMPLIANCE, CURRENT, CURRENT, CURRENT, COMPLIANCE);
        Function remove = sent.get(4);
        assertThat(((Address) remove.getInputParameters().get(0)).getValue()).isEqualTo(LEGACY);
        ArgumentCaptor<Erc3643ComplianceModule> saved = ArgumentCaptor.forClass(Erc3643ComplianceModule.class);
        verify(moduleRepository).save(saved.capture());
        assertThat(saved.getValue().getModuleAddress()).isEqualTo(CURRENT);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(ComplianceModuleReplacedEvent.class);
    }

    @Test
    @DisplayName("replace carries the legacy DB row's stored parameters when none are supplied, and retires that row")
    void replaceCarriesStoredParametersAndRetiresTheLegacyRow() {
        Erc3643ComplianceModule row = new Erc3643ComplianceModule();
        row.setSuiteId(suiteId);
        row.setModuleAddress(LEGACY);
        row.setModuleType("EWPG");
        row.setParameters(Map.of("maxInvestors", 150));
        when(moduleRepository.findBySuiteIdAndRemovedAtIsNull(suiteId)).thenReturn(List.of(row));

        service.replaceLegacyComplianceModule(suiteId, LEGACY, CURRENT, null, null, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(sent(new ArrayList<>())).extracting(Function::getName).contains("setMaxInvestors");
        assertThat(row.getRemovedAt()).isNotNull();
    }

    @Test
    @DisplayName("replace needs parameters when the legacy module has no stored configuration (it exposes no limit getters)")
    void replaceWithoutAnyParametersIsRefused() {
        assertThatThrownBy(() -> service.replaceLegacyComplianceModule(suiteId, LEGACY, CURRENT, null, null,
                UUID.randomUUID(), "REGISTRY_ADMIN")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parameters are required");
        assertThat(sent(new ArrayList<>())).isEmpty();
    }

    @Test
    @DisplayName("replace refuses to unbind a module that is not the legacy open-setter build")
    void replaceRefusesACurrentModule() {
        assertThatThrownBy(() -> service.replaceLegacyComplianceModule(suiteId, OTHER, CURRENT, "EWPG",
                Map.of("maxInvestors", 150), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a legacy");
        assertThat(sent(new ArrayList<>())).isEmpty();
    }

    @Test
    @DisplayName("a failing configuration unbinds the NEW module and leaves the legacy one enforcing")
    void failedConfigurationLeavesTheLegacyModuleBound() {
        doAnswer(inv -> {
            Function fn = inv.getArgument(4);
            if (fn.getName().equals("syncHolders")) {
                throw new IllegalStateException("out of gas");
            }
            return null;
        }).when(evm).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), anyString(), any(Function.class));

        assertThatThrownBy(() -> service.replaceLegacyComplianceModule(suiteId, LEGACY, CURRENT, "EWPG",
                Map.of("maxInvestors", 150), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("out of gas");

        List<String> targets = new ArrayList<>();
        List<Function> sent = sent(targets);
        Function last = sent.get(sent.size() - 1);
        assertThat(last.getName()).isEqualTo("removeModule");
        assertThat(((Address) last.getInputParameters().get(0)).getValue()).isEqualTo(CURRENT);
        verify(moduleRepository, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a repeated replace after a partial run only unbinds the legacy module")
    void repeatedReplaceResumesAtTheUnbinding() {
        newModuleAlreadyBound = true;

        service.replaceLegacyComplianceModule(suiteId, LEGACY, CURRENT, "EWPG", Map.of("maxInvestors", 150),
                UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(sent(new ArrayList<>())).extracting(Function::getName).containsExactly("removeModule");
    }
}
