package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.VaultRequestView;
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
        service = new Erc7540AdminService(deploymentRepository, requestRepository,
                evmTransactions, txService, events, evm, holderBlockGate);
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
