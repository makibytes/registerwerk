package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.VaultRequest;
import de.makibytes.registerwerk.asset.api.VaultRequestRepository;
import de.makibytes.registerwerk.asset.api.VaultRequestStatus;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
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
public class Erc7540AdminService {

    private static final Logger log = LoggerFactory.getLogger(Erc7540AdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final VaultRequestRepository vaultRequestRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;

    public Erc7540AdminService(
            AssetDeploymentRepository deploymentRepository,
            VaultRequestRepository vaultRequestRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService) {
        this.deploymentRepository = deploymentRepository;
        this.vaultRequestRepository = vaultRequestRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
    }

    // ── Fulfill deposit request ───────────────────────────────────────────────

    public UUID fulfillDepositRequest(UUID deploymentId, BigInteger onChainRequestId,
                                      BigDecimal navAtFulfill, UUID actorId) {
        log.info("Fulfilling deposit request={} on deployment={} at NAV={}",
                onChainRequestId, deploymentId, navAtFulfill);

        AssetDeployment dep = requireDeployment(deploymentId);

        Function fn = new Function("fulfillDepositRequest",
                Collections.singletonList(new Uint256(onChainRequestId)),
                Collections.emptyList());

        UUID txId = submitEvm(dep, fn, "fulfillDepositRequest",
                Map.of("requestId", onChainRequestId.toString()));

        vaultRequestRepository.findByAssetIdAndRequestId(dep.getAssetId(), onChainRequestId)
                .ifPresent(req -> {
                    req.setRequestStatus(VaultRequestStatus.FULFILLED);
                    req.setFulfilledAt(Instant.now());
                    req.setNavAtFulfill(navAtFulfill);
                    vaultRequestRepository.save(req);
                });

        return txId;
    }

    // ── Fulfill redeem request ────────────────────────────────────────────────

    public UUID fulfillRedeemRequest(UUID deploymentId, BigInteger onChainRequestId,
                                     BigDecimal navAtFulfill, UUID actorId) {
        log.info("Fulfilling redeem request={} on deployment={} at NAV={}",
                onChainRequestId, deploymentId, navAtFulfill);

        AssetDeployment dep = requireDeployment(deploymentId);

        Function fn = new Function("fulfillRedeemRequest",
                Collections.singletonList(new Uint256(onChainRequestId)),
                Collections.emptyList());

        UUID txId = submitEvm(dep, fn, "fulfillRedeemRequest",
                Map.of("requestId", onChainRequestId.toString()));

        vaultRequestRepository.findByAssetIdAndRequestId(dep.getAssetId(), onChainRequestId)
                .ifPresent(req -> {
                    req.setRequestStatus(VaultRequestStatus.FULFILLED);
                    req.setFulfilledAt(Instant.now());
                    req.setNavAtFulfill(navAtFulfill);
                    vaultRequestRepository.save(req);
                });

        return txId;
    }

    // ── Cancel request ────────────────────────────────────────────────────────

    public UUID cancelDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId) {
        log.info("Cancelling deposit request={} on deployment={}", onChainRequestId, deploymentId);
        return cancelRequest(deploymentId, onChainRequestId, "cancelDepositRequest");
    }

    public UUID cancelRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId) {
        log.info("Cancelling redeem request={} on deployment={}", onChainRequestId, deploymentId);
        return cancelRequest(deploymentId, onChainRequestId, "cancelRedeemRequest");
    }

    private UUID cancelRequest(UUID deploymentId, BigInteger onChainRequestId, String fnName) {
        AssetDeployment dep = requireDeployment(deploymentId);
        Function fn = new Function(fnName,
                Collections.singletonList(new Uint256(onChainRequestId)),
                Collections.emptyList());
        UUID txId = submitEvm(dep, fn, fnName, Map.of("requestId", onChainRequestId.toString()));

        vaultRequestRepository.findByAssetIdAndRequestId(dep.getAssetId(), onChainRequestId)
                .ifPresent(req -> {
                    req.setRequestStatus(VaultRequestStatus.CANCELLED);
                    vaultRequestRepository.save(req);
                });

        return txId;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VaultRequest> listPendingRequests(UUID assetId) {
        return vaultRequestRepository.findByAssetIdAndRequestStatus(assetId, VaultRequestStatus.PENDING);
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

    private UUID submitEvm(AssetDeployment dep, Function fn, String methodName, Map<String, Object> params) {
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);
        Credentials creds = evmContractService.credentials(descriptor);
        String txHash = evmContractService.submit(web3j, creds, dep.getContractAddress(), fn);
        return txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }
}
