package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registry-operator controls for EwpgERC7540 (async vault) request fulfillment.
 *
 * <p>Operators use this service to review and fulfill pending deposit/redeem requests
 * after striking a NAV (via {@link Erc4626AdminService#strikeNav}).
 *
 * <p>Fulfillment uses the NAV effective at call time — operators must ensure a current
 * NAV strike exists before fulfilling requests.
 */
@Service
@Transactional
public class Erc7540AdminService implements de.makibytes.registerwerk.blockchain.api.Erc7540AdminPort {

    private static final Logger log = LoggerFactory.getLogger(Erc7540AdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final VaultRequestRepository vaultRequestRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;
    private final ApplicationEventPublisher eventPublisher;

    public Erc7540AdminService(
            AssetDeploymentRepository deploymentRepository,
            VaultRequestRepository vaultRequestRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService,
            ApplicationEventPublisher eventPublisher) {
        this.deploymentRepository = deploymentRepository;
        this.vaultRequestRepository = vaultRequestRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
        this.eventPublisher = eventPublisher;
    }

    // ── Fulfill deposit request ───────────────────────────────────────────────

    public UUID fulfillDepositRequest(UUID deploymentId, BigInteger onChainRequestId,
                                      BigDecimal navAtFulfill, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, VaultRequestType.DEPOSIT);
        return fulfill(dep, request, navAtFulfill, actorId, actorRole);
    }

    // ── Fulfill redeem request ────────────────────────────────────────────────

    public UUID fulfillRedeemRequest(UUID deploymentId, BigInteger onChainRequestId,
                                     BigDecimal navAtFulfill, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, VaultRequestType.REDEEM);
        return fulfill(dep, request, navAtFulfill, actorId, actorRole);
    }

    @Override
    public UUID fulfillRequest(UUID deploymentId, BigInteger onChainRequestId,
                               BigDecimal navAtFulfill, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, null);
        return fulfill(dep, request, navAtFulfill, actorId, actorRole);
    }

    /**
     * Submits the fulfil tx and records it on the request row, but deliberately does <b>not</b>
     * flip {@code requestStatus} to {@code FULFILLED} yet — {@link EvmContractService#submit}
     * returns before any receipt exists, so at this point fulfilment is only submitted, not
     * confirmed. {@code VaultConfirmationListener} applies the status transition once the tx
     * reaches FINALIZED.
     */
    private UUID fulfill(AssetDeployment dep, VaultRequest request, BigDecimal navAtFulfill,
                         UUID actorId, String actorRole) {
        String functionName = request.getRequestType() == VaultRequestType.DEPOSIT
                ? "fulfillDepositRequest" : "fulfillRedeemRequest";
        log.info("Fulfilling {} request={} on deployment={} at NAV={}",
                request.getRequestType(), request.getRequestId(), dep.getId(), navAtFulfill);
        Function fn = new Function(functionName,
                Collections.singletonList(new Uint256(request.getRequestId())),
                Collections.emptyList());
        SubmittedTx tx = submitEvm(dep, fn, functionName,
                Map.of("requestId", request.getRequestId().toString(),
                        "navAtFulfill", navAtFulfill.toPlainString()), actorId, actorRole);
        request.setFulfilledTx(tx.txHash());
        request.setNavAtFulfill(navAtFulfill);
        vaultRequestRepository.save(request);
        return tx.txId();
    }

    // ── Cancel request ────────────────────────────────────────────────────────

    public UUID cancelDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole) {
        log.info("Cancelling deposit request={} on deployment={}", onChainRequestId, deploymentId);
        return cancelExpectedRequest(
                deploymentId, onChainRequestId, VaultRequestType.DEPOSIT, actorId, actorRole);
    }

    public UUID cancelRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole) {
        log.info("Cancelling redeem request={} on deployment={}", onChainRequestId, deploymentId);
        return cancelExpectedRequest(
                deploymentId, onChainRequestId, VaultRequestType.REDEEM, actorId, actorRole);
    }

    @Override
    public UUID cancelRequest(UUID deploymentId, BigInteger onChainRequestId,
                              UUID actorId, String actorRole) {
        return cancelExpectedRequest(deploymentId, onChainRequestId, null, actorId, actorRole);
    }

    /** Same "submitted, not yet confirmed" reasoning as {@link #fulfill} — {@code requestStatus}
     *  stays {@code PENDING} until {@code VaultConfirmationListener} confirms {@code cancelledTx}. */
    private UUID cancelExpectedRequest(UUID deploymentId, BigInteger onChainRequestId,
                                       VaultRequestType expectedType, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, expectedType);
        String fnName = request.getRequestType() == VaultRequestType.DEPOSIT
                ? "cancelDepositRequest" : "cancelRedeemRequest";
        Function fn = new Function(fnName,
                Collections.singletonList(new Uint256(onChainRequestId)),
                Collections.emptyList());
        SubmittedTx tx = submitEvm(dep, fn, fnName, Map.of("requestId", onChainRequestId.toString()), actorId, actorRole);
        request.setCancelledTx(tx.txHash());
        vaultRequestRepository.save(request);
        return tx.txId();
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VaultRequest> listRequests(UUID assetId, VaultRequestStatus status) {
        return vaultRequestRepository.findByAssetIdAndRequestStatus(assetId, status);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (dep.getContractAddress() == null) {
            throw new IllegalStateException("Vault contract not yet deployed: deploymentId=" + deploymentId);
        }
        return dep;
    }

    private VaultRequest requirePendingRequest(AssetDeployment deployment, BigInteger requestId,
                                               VaultRequestType expectedType) {
        VaultRequest request = vaultRequestRepository
                .findByAssetIdAndRequestId(deployment.getAssetId(), requestId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "VaultRequest", "requestId", requestId.toString()));
        if (expectedType != null && request.getRequestType() != expectedType) {
            throw new IllegalArgumentException("Vault request " + requestId + " is "
                    + request.getRequestType() + ", not " + expectedType);
        }
        if (request.getRequestStatus() != VaultRequestStatus.PENDING) {
            throw new IllegalStateException("Vault request " + requestId + " is already "
                    + request.getRequestStatus());
        }
        // requestStatus alone no longer proves this request is free to act on: fulfil/cancel now
        // leave it PENDING while a submitted tx awaits confirmation (see #fulfill,
        // #cancelExpectedRequest) — an in-flight request must not be re-submitted.
        if (request.getFulfilledTx() != null || request.getCancelledTx() != null) {
            throw new IllegalStateException("Vault request " + requestId
                    + " already has a submitted tx awaiting confirmation");
        }
        return request;
    }

    /** @param txId the {@code blockchain_transaction} tracking row's id (what callers of this
     *              service return to their own callers); {@code txHash} the actual on-chain
     *              transaction hash (what gets stored on {@code VaultRequest} so {@code
     *              VaultConfirmationListener} can poll it via {@code BlockchainTransactionService}). */
    private record SubmittedTx(UUID txId, String txHash) {}

    /**
     * Submits the on-chain transaction and publishes a {@link TokenAdminActionEvent} to the
     * audit log — the chokepoint all four public methods in this class funnel through, so
     * vault-request fulfillment AND cancellation (both previously unaudited) are covered.
     */
    private SubmittedTx submitEvm(AssetDeployment dep, Function fn, String methodName, Map<String, Object> params,
                           UUID actorId, String actorRole) {
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);
        EvmSigner signer = evmContractService.signer(descriptor);
        String txHash = evmContractService.submit(web3j, signer, dep.getContractAddress(), fn);

        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), methodName, actorId, actorRole, params));

        UUID txId = txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
        return new SubmittedTx(txId, txHash);
    }
}
