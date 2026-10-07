package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.VaultDealingState;
import de.makibytes.registerwerk.blockchain.api.VaultRequestView;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import org.springframework.mock.env.MockEnvironment;
import org.web3j.abi.datatypes.generated.Uint256;
import java.time.Instant;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Erc7540AdminServiceTest {

    @Mock AssetDeploymentRepository deploymentRepository;
    @Mock VaultRequestRepository requestRepository;
    @Mock DurableEvmTransactionGateway evmTransactions;
    @Mock BlockchainTransactionService txService;
    @Mock ApplicationEventPublisher events;
    @Mock EvmContractService evm;
    @Mock HolderBlockGate holderBlockGate;
    @Mock VaultDealingReader dealingReader;
    @Mock AssetLookupPort assetLookup;

    private static final String OWNER = "0x00000000000000000000000000000000000000aa";
    private static final String CONTROLLER = "0x00000000000000000000000000000000000000cc";
    private static final String PAYER = "0x00000000000000000000000000000000000000bb";

    private Erc7540AdminService service;
    private AssetDeployment deployment;
    private UUID deploymentId;
    /** Addresses the mocked vault reports as frozen via isFrozen(address). */
    private final Set<String> frozenOnChain = new HashSet<>();

    @BeforeEach
    void setUp() {
        service = newService(false, "17:00");
        deploymentId = UUID.randomUUID();
        deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(UUID.randomUUID());
        deployment.setChainConfigId(UUID.randomUUID());
        deployment.setChain(Chain.ETHEREUM);
        deployment.setNetwork(Network.TESTNET);
        deployment.setContractAddress("0x0000000000000000000000000000000000000001");

        Web3j web3j = mock(Web3j.class);
        lenient().when(evm.evmClient(deployment.getChainConfigId())).thenReturn(web3j);
        lenient().when(evm.call(eq(web3j), eq(deployment.getContractAddress()), any(Function.class)))
                .thenAnswer(inv -> {
                    Function fn = inv.getArgument(2);
                    String who = ((Address) fn.getInputParameters().get(0)).getValue();
                    return List.<Type>of(new Bool(frozenOnChain.contains(who)));
                });
        lenient().when(evm.simulateRevert(any(), any(), any(Function.class))).thenReturn(Optional.empty());
    }

    private Erc7540AdminService newService(boolean production, String defaultCutoff) {
        MockEnvironment env = new MockEnvironment();
        if (production) env.setProperty("registerwerk.production-mode", "true");
        return new Erc7540AdminService(deploymentRepository, requestRepository,
                evmTransactions, txService, events, evm, holderBlockGate,
                dealingReader, new VaultDealingSettings(env, defaultCutoff, 86_400L), assetLookup);
    }

    @Test
    void genericFulfillSubmitsTxButDoesNotFlipStatusUntilConfirmed() {
        BigInteger requestId = BigInteger.TEN;
        VaultRequest request = request(requestId, VaultRequestType.REDEEM, VaultRequestStatus.PENDING);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndChainConfigIdAndRequestId(deployment.getAssetId(), deployment.getChainConfigId(), requestId))
                .thenReturn(Optional.of(request));
        when(evmTransactions.submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), any(Function.class), any()))
                .thenReturn("0xtx");
        when(txService.record(eq("0xtx"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        service.fulfillRequest(deploymentId, requestId, UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<Function> function = ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), function.capture(), any());
        assertThat(function.getValue().getName()).isEqualTo("fulfillRedeemRequest");
        // submit() returns before any receipt exists — status must stay PENDING until
        // VaultConfirmationListener confirms fulfilledTx (see VaultConfirmationListenerTest).
        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(request.getFulfilledTx()).isEqualTo("0xtx");
        assertThat(request.getFulfilledAt()).isNull();
        assertThat(request.isConfirmed()).isFalse();
        // T1-08: nothing operator-supplied is recorded as the executed NAV at submission.
        assertThat(request.getNavAtFulfill()).isNull();
        verify(requestRepository).save(request);
    }

    @Test
    void alreadyCompletedRequestIsRejectedBeforeOnChainSubmission() {
        BigInteger requestId = BigInteger.ONE;
        VaultRequest request = request(requestId, VaultRequestType.DEPOSIT, VaultRequestStatus.FULFILLED);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndChainConfigIdAndRequestId(deployment.getAssetId(), deployment.getChainConfigId(), requestId))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.cancelRequest(
                deploymentId, requestId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already FULFILLED");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void requestWithUnconfirmedFulfilledTxCannotBeResubmitted() {
        // requestStatus stays PENDING while a submitted tx awaits confirmation (see
        // genericFulfillSubmitsTxButDoesNotFlipStatusUntilConfirmed) — requirePendingRequest must
        // still reject a second submission attempt on the same request.
        BigInteger requestId = BigInteger.valueOf(7);
        VaultRequest request = request(requestId, VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        request.setFulfilledTx("0xalready-submitted");
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndChainConfigIdAndRequestId(deployment.getAssetId(), deployment.getChainConfigId(), requestId))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.fulfillRequest(
                deploymentId, requestId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting confirmation");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void cancelSubmitsTxButDoesNotFlipStatusUntilConfirmed() {
        BigInteger requestId = BigInteger.valueOf(3);
        VaultRequest request = request(requestId, VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndChainConfigIdAndRequestId(deployment.getAssetId(), deployment.getChainConfigId(), requestId))
                .thenReturn(Optional.of(request));
        when(evmTransactions.submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), any(Function.class), any()))
                .thenReturn("0xcanceltx");
        when(txService.record(eq("0xcanceltx"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        service.cancelRequest(deploymentId, requestId, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(request.getCancelledTx()).isEqualTo("0xcanceltx");
        assertThat(request.isConfirmed()).isFalse();
        verify(requestRepository).save(request);
    }

    // ── T1-05: compliance hold (freeze-in-place) ─────────────────────────────

    @Test
    void fulfilIsRefusedWhenOwnerIsFrozenOnTheVault() {
        VaultRequest request = stubPending(BigInteger.valueOf(21), VaultRequestType.REDEEM);
        frozenOnChain.add(OWNER);

        assertThatThrownBy(() -> service.fulfillRequest(deploymentId, request.getRequestId(),
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining(OWNER)
                .hasMessageContaining("frozen");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
        assertThat(request.getFulfilledTx()).isNull();
    }

    @Test
    void fulfilIsRefusedWhenOwnerIsUnderSperrvermerk() {
        VaultRequest request = stubPending(BigInteger.valueOf(22), VaultRequestType.DEPOSIT);
        when(holderBlockGate.isBlocked(null, OWNER)).thenReturn(true);

        assertThatThrownBy(() -> service.fulfillRequest(deploymentId, request.getRequestId(),
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("Sperrvermerk");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void fulfilChecksTheOwnerNotTheController() {
        // DEV veto: the controller never receives value — a frozen controller must not stall it.
        VaultRequest request = stubPending(BigInteger.valueOf(23), VaultRequestType.REDEEM);
        frozenOnChain.add(CONTROLLER);
        stubSubmit("0xfulfil");

        service.fulfillRequest(deploymentId, request.getRequestId(), UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(request.getFulfilledTx()).isEqualTo("0xfulfil");
    }

    @Test
    void fulfilFailsClosedWhenFreezeStatusCannotBeRead() {
        VaultRequest request = stubPending(BigInteger.valueOf(24), VaultRequestType.REDEEM);
        when(evm.call(any(), eq(deployment.getContractAddress()), any(Function.class)))
                .thenThrow(new RuntimeException("eth_call error: connection refused"));

        assertThatThrownBy(() -> service.fulfillRequest(deploymentId, request.getRequestId(),
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("fail closed");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void depositCancelChecksTheRefundRecipientWhichIsThePayer() {
        VaultRequest request = stubPending(BigInteger.valueOf(25), VaultRequestType.DEPOSIT);
        request.setPayerAddr(PAYER);
        frozenOnChain.add(PAYER);

        assertThatThrownBy(() -> service.cancelRequest(deploymentId, request.getRequestId(),
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining(PAYER);
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void listViewFlagsHeldRequestsAsComplianceHold() {
        VaultRequest held = request(BigInteger.valueOf(26), VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        held.setPayerAddr(PAYER);
        VaultRequest clear = request(BigInteger.valueOf(27), VaultRequestType.REDEEM, VaultRequestStatus.PENDING);
        clear.setOwnerAddr("0x00000000000000000000000000000000000000dd");
        frozenOnChain.add(PAYER);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestStatus(deployment.getAssetId(), VaultRequestStatus.PENDING))
                .thenReturn(List.of(held, clear));

        List<VaultRequestView> views = service.listRequestViews(deploymentId, VaultRequestStatus.PENDING);

        assertThat(views).extracting(VaultRequestView::complianceHold).containsExactly(true, false);
        assertThat(views.get(0).complianceHoldReason()).contains(PAYER);
    }

    // ── Revert translation (pre-flight) ──────────────────────────────────────

    @Test
    void preflightRevertIsTranslatedAndNothingIsSubmitted() {
        VaultRequest request = stubPending(BigInteger.valueOf(28), VaultRequestType.REDEEM);
        when(evm.simulateRevert(eq(deployment.getChainConfigId()), eq(deployment.getContractAddress()), any(Function.class)))
                .thenReturn(Optional.of("execution reverted: EwpgERC7540: insufficient settled liquidity"));

        assertThatThrownBy(() -> service.fulfillRequest(deploymentId, request.getRequestId(),
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("settled liquidity");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    // ── T1-07: forward pricing / dealing cut-off ─────────────────────────────

    private static final Instant NAV_STRUCK = Instant.parse("2027-01-14T18:00:00Z");
    private static final Instant NEXT_DEALING = Instant.parse("2027-01-15T17:00:00Z");

    private void stubVaultAsset(TokenStandard standard) {
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        lenient().when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        lenient().when(deploymentRepository.findByAssetId(deployment.getAssetId())).thenReturn(List.of(deployment));
        lenient().when(assetLookup.findById(deployment.getAssetId())).thenReturn(Optional.of(
                new AssetLookupPort.AssetInfo(deployment.getAssetId(), "Aurora Fund", "DE000A3H9PQ9", standard,
                        "ETHEREUM", "TESTNET", UUID.randomUUID(), "A-1", "ISSUED")));
    }

    private static VaultDealingState configuredState() {
        return new VaultDealingState(true, true, true, 61_200, 86_400L, NEXT_DEALING, NAV_STRUCK);
    }

    private static VaultDealingState unconfiguredState() {
        return new VaultDealingState(true, true, false, null, null, null, NAV_STRUCK);
    }

    @Test
    void setDealingCutoffSubmitsTheRegistryTxAndAudits() {
        stubVaultAsset(TokenStandard.ERC7540);
        stubSubmit("0xcutoff");
        UUID actor = UUID.randomUUID();

        service.setDealingCutoff(deploymentId, 61_200, 86_400L, actor, "REGISTRY_ADMIN");

        ArgumentCaptor<Function> function = ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), function.capture(), any());
        assertThat(function.getValue().getName()).isEqualTo("setDealingCutoff");
        assertThat(((Uint256) function.getValue().getInputParameters().get(0)).getValue()).isEqualTo(BigInteger.valueOf(61_200));
        assertThat(((Uint256) function.getValue().getInputParameters().get(1)).getValue()).isEqualTo(BigInteger.valueOf(86_400));
        verify(evm).simulateRevert(eq(deployment.getChainConfigId()), eq(deployment.getContractAddress()), any(Function.class));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        TokenAdminActionEvent audited = (TokenAdminActionEvent) event.getValue();
        assertThat(audited.methodName()).isEqualTo("setDealingCutoff");
        assertThat(audited.actorId()).isEqualTo(actor);
        assertThat(audited.params()).containsEntry("cutoffUtc", "17:00").containsEntry("periodSeconds", "86400");
    }

    @Test
    void setDealingCutoffRejectsValuesTheContractOrOperationsWouldNotAccept() {
        stubVaultAsset(TokenStandard.ERC7540);
        for (long[] bad : new long[][] {{86_400, 86_400}, {-1, 86_400}, {61_200, 0}, {61_200, 60}, {61_200, 90L * 86_400}}) {
            assertThatThrownBy(() -> service.setDealingCutoff(deploymentId, (int) bad[0], bad[1],
                    UUID.randomUUID(), "REGISTRY_ADMIN"))
                    .as("cutoff=%d period=%d", bad[0], bad[1])
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void setDealingCutoffIsRefusedForAVaultThatIsNotErc7540() {
        stubVaultAsset(TokenStandard.ERC4626);

        assertThatThrownBy(() -> service.setDealingCutoff(deploymentId, 61_200, 86_400L,
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ERC-7540");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void setDealingCutoffSurfacesAPreflightRevert() {
        stubVaultAsset(TokenStandard.ERC7540);
        when(evm.simulateRevert(any(), any(), any(Function.class)))
                .thenReturn(Optional.of("execution reverted: EwpgERC7540: not registry"));

        assertThatThrownBy(() -> service.setDealingCutoff(deploymentId, 61_200, 86_400L,
                UUID.randomUUID(), "REGISTRY_ADMIN")).isInstanceOf(IllegalStateException.class);
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void afterDeploymentTheConfiguredDefaultsAreSentAsTheSystemActor() {
        stubVaultAsset(TokenStandard.ERC7540);
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(unconfiguredState()));
        stubSubmit("0xdefaults");

        service.configureDealingCutoffAfterDeployment(deploymentId);

        ArgumentCaptor<Function> function = ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), function.capture(), any());
        assertThat(function.getValue().getName()).isEqualTo("setDealingCutoff");
        assertThat(((Uint256) function.getValue().getInputParameters().get(0)).getValue()).isEqualTo(BigInteger.valueOf(61_200));
        assertThat(((Uint256) function.getValue().getInputParameters().get(1)).getValue()).isEqualTo(BigInteger.valueOf(86_400));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(((TokenAdminActionEvent) event.getValue()).actorRole()).isEqualTo("SYSTEM");
    }

    @Test
    void afterDeploymentAnAlreadyConfiguredVaultIsLeftAlone() {
        stubVaultAsset(TokenStandard.ERC7540);
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(configuredState()));

        service.configureDealingCutoffAfterDeployment(deploymentId);

        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void afterDeploymentNothingIsSentWhenTheAutomaticDefaultIsSwitchedOff() {
        stubVaultAsset(TokenStandard.ERC7540);
        Erc7540AdminService off = newService(false, "off");

        off.configureDealingCutoffAfterDeployment(deploymentId);

        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    @Test
    void afterDeploymentAFailureNeverPropagatesToTheDeploymentConfirmation() {
        stubVaultAsset(TokenStandard.ERC7540);
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(unconfiguredState()));
        when(evmTransactions.submit(any(), any(), any(Function.class), any()))
                .thenThrow(new IllegalStateException("signer unavailable"));

        service.configureDealingCutoffAfterDeployment(deploymentId); // must not throw
    }

    @Test
    void productionRefusesAVaultWithoutADealingCutoff() {
        stubVaultAsset(TokenStandard.ERC7540);
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(unconfiguredState()));
        Erc7540AdminService production = newService(true, "17:00");

        assertThatThrownBy(() -> production.requireDealingCutoffConfigured(deployment.getAssetId(), "subscription"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("dealing cut-off")
                .hasMessageContaining(deployment.getContractAddress());
    }

    @Test
    void productionFailsClosedWhenTheDealingCutoffCannotBeRead() {
        stubVaultAsset(TokenStandard.ERC7540);
        when(dealingReader.readState(deployment)).thenReturn(Optional.empty());
        Erc7540AdminService production = newService(true, "17:00");

        assertThatThrownBy(() -> production.requireDealingCutoffConfigured(deployment.getAssetId(), "subscription"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("could not read");
    }

    @Test
    void productionAcceptsAConfiguredVaultAndIgnoresOtherStandards() {
        stubVaultAsset(TokenStandard.ERC7540);
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(configuredState()));
        Erc7540AdminService production = newService(true, "17:00");

        production.requireDealingCutoffConfigured(deployment.getAssetId(), "subscription");

        stubVaultAsset(TokenStandard.ERC20);
        production.requireDealingCutoffConfigured(deployment.getAssetId(), "subscription");
        verify(dealingReader, org.mockito.Mockito.times(1)).readState(any());
    }

    @Test
    void demoModeNeverReadsTheChainAndNeverRefuses() {
        stubVaultAsset(TokenStandard.ERC7540);

        service.requireDealingCutoffConfigured(deployment.getAssetId(), "subscription");

        org.mockito.Mockito.verifyNoInteractions(dealingReader);
    }

    @Test
    void readinessListsConfirmedVaultsWithoutADealingCutoff() {
        stubVaultAsset(TokenStandard.ERC7540);
        AssetLookupPort.AssetInfo vault = assetLookup.findById(deployment.getAssetId()).orElseThrow();
        AssetLookupPort.AssetInfo token = new AssetLookupPort.AssetInfo(UUID.randomUUID(), "Plain token", null,
                TokenStandard.ERC20, "ETHEREUM", "TESTNET", UUID.randomUUID(), "A-2", "ISSUED");
        when(assetLookup.findAll()).thenReturn(List.of(vault, token));
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(unconfiguredState()));

        assertThat(service.listVaultsWithoutDealingCutoff())
                .singleElement()
                .satisfies(line -> assertThat(line).contains("Aurora Fund").contains(deployment.getContractAddress()));

        when(dealingReader.readState(deployment)).thenReturn(Optional.of(configuredState()));
        assertThat(service.listVaultsWithoutDealingCutoff()).isEmpty();

        when(dealingReader.readState(deployment)).thenReturn(Optional.empty());
        assertThat(service.listVaultsWithoutDealingCutoff()).singleElement()
                .satisfies(line -> assertThat(line).contains("unreadable"));
    }

    @Test
    void dealingStateIsNotApplicableToOtherStandardsAndDelegatesForErc7540() {
        stubVaultAsset(TokenStandard.ERC4626);
        assertThat(service.dealingState(deploymentId).applicable()).isFalse();

        stubVaultAsset(TokenStandard.ERC7540);
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(configuredState()));
        assertThat(service.dealingState(deploymentId)).isEqualTo(configuredState());

        when(dealingReader.readState(deployment)).thenReturn(Optional.empty());
        VaultDealingState unreadable = service.dealingState(deploymentId);
        assertThat(unreadable.applicable()).isTrue();
        assertThat(unreadable.available()).isFalse();
    }

    @Test
    void listViewShowsEachRequestsDealingPointAndWhetherItAwaitsANavStrike() {
        VaultRequest waiting = request(BigInteger.valueOf(41), VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        VaultRequest struck = request(BigInteger.valueOf(42), VaultRequestType.REDEEM, VaultRequestStatus.PENDING);
        VaultRequest legacy = request(BigInteger.valueOf(43), VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        for (VaultRequest r : List.of(waiting, struck, legacy)) r.setOwnerAddr(OWNER);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestStatus(deployment.getAssetId(), VaultRequestStatus.PENDING))
                .thenReturn(List.of(waiting, struck, legacy));
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(configuredState()));
        when(dealingReader.dealingPointOf(deployment, BigInteger.valueOf(41)))
                .thenReturn(Optional.of(BigInteger.valueOf(NEXT_DEALING.getEpochSecond())));          // after the NAV
        when(dealingReader.dealingPointOf(deployment, BigInteger.valueOf(42)))
                .thenReturn(Optional.of(BigInteger.valueOf(NAV_STRUCK.getEpochSecond() - 3_600)));    // before the NAV
        when(dealingReader.dealingPointOf(deployment, BigInteger.valueOf(43)))
                .thenReturn(Optional.of(BigInteger.ZERO));                                              // legacy, pre-cut-off

        List<VaultRequestView> views = service.listRequestViews(deploymentId, VaultRequestStatus.PENDING);

        assertThat(views.get(0).dealingPoint()).isEqualTo(NEXT_DEALING);
        assertThat(views.get(0).awaitingNavStrike()).isTrue();
        assertThat(views.get(1).dealingPoint()).isEqualTo(NAV_STRUCK.minusSeconds(3_600));
        assertThat(views.get(1).awaitingNavStrike()).isFalse();
        assertThat(views.get(2).dealingPoint()).isNull();
        assertThat(views.get(2).awaitingNavStrike()).isFalse();
    }

    @Test
    void listViewDoesNotReadDealingPointsOfAVaultWithoutACutoff() {
        VaultRequest pending = request(BigInteger.valueOf(44), VaultRequestType.DEPOSIT, VaultRequestStatus.PENDING);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndRequestStatus(deployment.getAssetId(), VaultRequestStatus.PENDING))
                .thenReturn(List.of(pending));
        when(dealingReader.readState(deployment)).thenReturn(Optional.of(unconfiguredState()));

        List<VaultRequestView> views = service.listRequestViews(deploymentId, VaultRequestStatus.PENDING);

        assertThat(views.get(0).dealingPoint()).isNull();
        verify(dealingReader, never()).dealingPointOf(any(), any());
    }

    // ── Force-cancel ─────────────────────────────────────────────────────────

    @Test
    void forceCancelSubmitsWithDestinationAndLegalBasis() {
        VaultRequest request = stubPending(BigInteger.valueOf(29), VaultRequestType.REDEEM);
        frozenOnChain.add(OWNER); // the reason force-cancel exists — must not be refused for it
        stubSubmit("0xforce");
        String destination = "0x00000000000000000000000000000000000000EE";

        service.forceCancelRequest(deploymentId, request.getRequestId(), destination,
                "LG Frankfurt 2-04 O 1/26", UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<Function> function = ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), function.capture(), any());
        assertThat(function.getValue().getName()).isEqualTo("forceCancelRedeemRequest");
        assertThat(((Address) function.getValue().getInputParameters().get(1)).getValue())
                .isEqualToIgnoringCase(destination);
        assertThat(((Utf8String) function.getValue().getInputParameters().get(2)).getValue())
                .isEqualTo("LG Frankfurt 2-04 O 1/26");
        assertThat(request.getCancelledTx()).isEqualTo("0xforce");
        assertThat(request.getForcedToAddr()).isEqualToIgnoringCase(destination);
        assertThat(request.getLegalBasis()).isEqualTo("LG Frankfurt 2-04 O 1/26");
        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
    }

    @Test
    void forceCancelToTheVaultItselfIsRejected() {
        VaultRequest request = stubPending(BigInteger.valueOf(30), VaultRequestType.DEPOSIT);

        assertThatThrownBy(() -> service.forceCancelRequest(deploymentId, request.getRequestId(),
                deployment.getContractAddress(), "court order", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vault itself");
        verify(evmTransactions, never()).submit(any(), any(), any(Function.class), any());
    }

    private VaultRequest stubPending(BigInteger requestId, VaultRequestType type) {
        VaultRequest request = request(requestId, type, VaultRequestStatus.PENDING);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(requestRepository.findByAssetIdAndChainConfigIdAndRequestId(deployment.getAssetId(), deployment.getChainConfigId(), requestId))
                .thenReturn(Optional.of(request));
        return request;
    }

    private void stubSubmit(String txHash) {
        when(evmTransactions.submit(eq(deployment.getChainConfigId()),
                eq(deployment.getContractAddress()), any(Function.class), any()))
                .thenReturn(txHash);
        when(txService.record(eq(txHash), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
    }

    private VaultRequest request(BigInteger requestId, VaultRequestType type, VaultRequestStatus status) {
        VaultRequest request = new VaultRequest();
        request.setAssetId(deployment.getAssetId());
        request.setRequestId(requestId);
        request.setRequestType(type);
        request.setRequestStatus(status);
        request.setOwnerAddr(OWNER);
        request.setControllerAddr(CONTROLLER);
        return request;
    }
}
