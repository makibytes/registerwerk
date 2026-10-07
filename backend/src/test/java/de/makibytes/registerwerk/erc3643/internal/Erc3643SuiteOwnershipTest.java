package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModuleRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuer;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuerRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.erc3643.events.ClaimTopicAddedEvent;
import de.makibytes.registerwerk.erc3643.events.TrustedIssuerAddedEvent;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.Web3j;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1-20: T-REX's {@code OwnableOnceNext2StepUpgradeable} leaves the registry wallet only the
 * <i>pending</i> owner of every suite contract after {@code deployTREXSuite}. Only the compliance
 * used to be accepted, so TIR {@code addTrustedIssuer}/{@code removeTrustedIssuer} and CTR
 * {@code addClaimTopic} ({@code onlyOwner}) reverted on every freshly deployed suite.
 */
@ExtendWith(MockitoExtension.class)
class Erc3643SuiteOwnershipTest {

    private static final String TOKEN = "0x00000000000000000000000000000000000000a1";
    private static final String IR = "0x00000000000000000000000000000000000000a2";
    private static final String TIR = "0x00000000000000000000000000000000000000a3";
    private static final String CTR = "0x00000000000000000000000000000000000000a4";
    private static final String COMPLIANCE = "0x00000000000000000000000000000000000000a5";
    private static final String SIGNER = "0x00000000000000000000000000000000000000e0";
    private static final String TREX_FACTORY = "0x00000000000000000000000000000000000000f0";
    private static final String ISSUER = "0x1111111111111111111111111111111111111111";

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

    private Erc3643LifecycleService lifecycle;
    private Erc3643Suite suite;
    private EvmSigner signer;
    private final UUID suiteId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private final Web3j web3j = mock(Web3j.class);

    /** Owner each suite contract reports; the inner TREXFactory until acceptOwnership. */
    private String onChainOwner = TREX_FACTORY;

    @BeforeEach
    void setUp() {
        lifecycle = new Erc3643LifecycleService(suiteRepository, complianceModuleRepository,
                trustedIssuerRepository, claimTopicRepository, identityRegistryRepository,
                deploymentRepository, eventPublisher, evmContractService, evmTransactions,
                blockchainClientRegistry, txService, holderBlockGate,
                org.mockito.Mockito.mock(de.makibytes.registerwerk.deployment.api.AssetLookupPort.class),
                org.mockito.Mockito.mock(de.makibytes.registerwerk.kyc.api.OutboundDestinationGate.class),
                org.mockito.Mockito.mock(de.makibytes.registerwerk.travelrule.api.TravelRuleGate.class),
                holderRepository);

        suite = new Erc3643Suite();
        suite.setId(suiteId);
        suite.setAssetDeploymentId(deploymentId);
        suite.setTokenAddress(TOKEN);
        suite.setIdentityRegistryAddress(IR);
        suite.setTrustedIssuersRegistry(TIR);
        suite.setClaimTopicsRegistry(CTR);
        suite.setComplianceAddress(COMPLIANCE);
        when(suiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));

        AssetDeployment dep = new AssetDeployment();
        dep.setId(deploymentId);
        dep.setChainConfigId(chainConfigId);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.MAINNET);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(dep));
        when(blockchainClientRegistry.getEvmClient(any(ChainDescriptor.class))).thenReturn(web3j);
        signer = mock(EvmSigner.class);
        when(signer.address()).thenReturn(SIGNER);
        when(evmContractService.signer(any(ChainDescriptor.class))).thenReturn(signer);
        when(evmContractService.call(eq(web3j), anyString(), any(Function.class))).thenAnswer(inv -> {
            Function fn = inv.getArgument(2);
            if (!fn.getName().equals("owner")) {
                throw new IllegalArgumentException("unexpected call " + fn.getName());
            }
            return List.<Type>of(new Address(onChainOwner));
        });
        org.mockito.Mockito.lenient().when(trustedIssuerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(claimTopicRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Function named(String name) {
        return argThat(fn -> fn != null && fn.getName().equals(name));
    }

    @Test
    void addTrustedIssuerAcceptsPendingTirOwnershipFirst() {
        lifecycle.addTrustedIssuer(suiteId, ISSUER, List.of(1L), null, UUID.randomUUID(), "REGISTRY_ADMIN");

        InOrder order = inOrder(evmContractService);
        order.verify(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), eq(TIR),
                named("acceptOwnership"));
        order.verify(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), eq(TIR),
                named("addTrustedIssuer"));
        ArgumentCaptor<TrustedIssuerAddedEvent> event = ArgumentCaptor.forClass(TrustedIssuerAddedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().details()).containsEntry("tirOwnershipAccepted", true);
    }

    @Test
    void addTrustedIssuerSkipsAcceptWhenAlreadyOwner() {
        onChainOwner = SIGNER;

        lifecycle.addTrustedIssuer(suiteId, ISSUER, List.of(1L), null, UUID.randomUUID(), "REGISTRY_ADMIN");

        verify(evmContractService, never()).send(any(UUID.class), any(), any(), anyString(), named("acceptOwnership"));
        verify(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), eq(TIR),
                named("addTrustedIssuer"));
    }

    @Test
    void removeTrustedIssuerAcceptsPendingTirOwnershipFirst() {
        UUID issuerId = UUID.randomUUID();
        Erc3643TrustedIssuer issuer = new Erc3643TrustedIssuer();
        issuer.setId(issuerId);
        issuer.setSuiteId(suiteId);
        issuer.setIssuerAddress(ISSUER);
        when(trustedIssuerRepository.findById(issuerId)).thenReturn(Optional.of(issuer));

        lifecycle.removeTrustedIssuer(suiteId, issuerId, UUID.randomUUID(), "REGISTRY_ADMIN");

        InOrder order = inOrder(evmContractService);
        order.verify(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), eq(TIR),
                named("acceptOwnership"));
        order.verify(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), eq(TIR),
                named("removeTrustedIssuer"));
    }

    @Test
    void addRequiredClaimTopicAcceptsPendingCtrOwnershipFirst() {
        lifecycle.addRequiredClaimTopic(suiteId, 3L, "Accreditation", UUID.randomUUID(), "REGISTRY_ADMIN");

        InOrder order = inOrder(evmContractService);
        order.verify(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), eq(CTR),
                named("acceptOwnership"));
        order.verify(evmContractService).send(eq(chainConfigId), eq(web3j), any(EvmSigner.class), eq(CTR),
                named("addClaimTopic"));
        ArgumentCaptor<ClaimTopicAddedEvent> event = ArgumentCaptor.forClass(ClaimTopicAddedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().details()).containsEntry("ctrOwnershipAccepted", true);
    }

    @Test
    void deployTimeAcceptanceCoversAllFiveTransferredContractsBestEffort() {
        Erc3643DeploymentService deploymentService = new Erc3643DeploymentService(blockchainClientRegistry,
                mock(OnchainIdentityRepository.class), mock(OnchainClaimRepository.class), suiteRepository,
                claimTopicRepository, deploymentRepository, mock(AssetLookupPort.class), eventPublisher,
                mock(ExplorerUrlBuilder.class), mock(ChainConfigRepository.class), evmContractService,
                evmTransactions, mock(ContractAddressConfig.class), mock(ClaimSigningService.class), txService);
        // One failing contract must not stop the others (or the deployment).
        when(evmContractService.send(eq(chainConfigId), eq(web3j), eq(signer), eq(IR), any(Function.class)))
                .thenThrow(new RuntimeException("rpc down"));

        deploymentService.acceptSuiteOwnership(chainConfigId, web3j, signer, suite);

        for (String contract : List.of(TOKEN, IR, TIR, CTR, COMPLIANCE)) {
            verify(evmContractService, times(1)).send(eq(chainConfigId), eq(web3j), eq(signer), eq(contract),
                    named("acceptOwnership"));
        }
    }
}
