package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Persists asynchronous deployment outcomes inside a fresh, auditable transaction. */
@Component
public class AssetDeploymentCompletionWriter {

    private static final Logger log = LoggerFactory.getLogger(AssetDeploymentCompletionWriter.class);

    /**
     * Chains whose deployment future completes only after the protocol has committed the write —
     * safe to mark CONFIRMED the instant we see it. Stellar's
     * {@code StellarAssetService.createStellarAsset} only completes its
     * future after Horizon's synchronous {@code submitTransaction} call returns success, and
     * Horizon blocks until the ledger actually closes (see {@code StellarTransferSyncService}
     * class javadoc). Canton operations use Ledger API {@code submitAndWaitForTransaction}, which
     * returns an update ID only for a committed transaction. Canton has no contract address in
     * the EVM sense, so finality must not depend on {@code contractAddress()} being non-null.
     *
     * <p>Every other chain that can reach this branch is excluded on purpose:
     * <ul>
     *   <li>EVM (ERC20/721/1155/3525/4626/7540, ERC-3643): {@code contractAddress} is resolved
     *       from the factory's deployed-address event log, but that only requires the receipt to
     *       exist at all — one confirmation, not the configured confirmation depth. Immediate
     *       presence alone cannot mark the deployment confirmed.</li>
     *   <li>Starknet: {@code contractAddress} is the UDC-precomputed address, known
     *       deterministically before the transaction is even broadcast — its presence proves
     *       nothing about whether the transaction ever landed, let alone reached L1 acceptance.</li>
     * </ul>
     * Both of those defer to {@code AssetDeploymentService#pollPendingDeploymentConfirmations},
     * which applies the same confirmation-depth (EVM) / L1-acceptance (Starknet) policy as
     * token-transfer finality. Solana returns its locally generated mint address immediately but
     * remains PENDING until the signature reaches protocol-native {@code finalized} commitment.
     */
    private static final Set<Chain> IMMEDIATE_CONFIRM_CHAINS =
            EnumSet.of(Chain.STELLAR, Chain.CANTON);

    private final AssetDeploymentRepository repository;
    private final ApplicationEventPublisher events;

    public AssetDeploymentCompletionWriter(
            AssetDeploymentRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    public void markFailed(UUID deploymentId, UUID actorId, Throwable failure) {
        repository.findById(deploymentId).ifPresentOrElse(deployment -> {
            if (deployment.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED) {
                log.warn("Ignoring late deployment failure for already confirmed deploymentId={}", deploymentId);
                return;
            }
            deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
            repository.save(deployment);
            String reason = failure != null && failure.getMessage() != null
                    ? failure.getMessage() : "Deployment failed";
            events.publishEvent(new DeploymentFailedEvent(deploymentId, actorId, null, reason));
        }, () -> log.warn("Deployment disappeared before failure could be recorded: id={}", deploymentId));
    }

    @Transactional
    public void markSubmitted(UUID deploymentId, UUID actorId, TokenDeploymentResult result) {
        repository.findById(deploymentId).ifPresentOrElse(deployment -> {
            if (deployment.getDeploymentStatus() == AssetDeployment.DeploymentStatus.FAILED) {
                log.warn("Ignoring late deployment success for already failed deploymentId={}", deploymentId);
                return;
            }
            deployment.setDeployedByTx(result.txHash());
            if (result.contractAddress() != null && !result.contractAddress().isBlank()) {
                deployment.setContractAddress(result.contractAddress());
                if (!IMMEDIATE_CONFIRM_CHAINS.contains(deployment.getChain())) {
                    // Address known (receipt seen / UDC-precomputed), but that alone does not
                    // prove finality for this chain (see IMMEDIATE_CONFIRM_CHAINS javadoc) —
                    // stays PENDING with the address visible; AssetDeploymentService's scheduled
                    // confirmation poll flips it to CONFIRMED once the chain-specific policy is
                    // satisfied.
                    log.debug("Deployment id={} chain={} has a candidate address {} but requires "
                                    + "chain-read confirmation before CONFIRMED; staying PENDING.",
                            deploymentId, deployment.getChain(), result.contractAddress());
                }
            }
            if (IMMEDIATE_CONFIRM_CHAINS.contains(deployment.getChain())) {
                deployment.setDeployedAt(Instant.now());
                deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
                events.publishEvent(new DeploymentConfirmedEvent(
                        deploymentId, actorId, null, result.contractAddress(), result.txHash()));
            }
            repository.save(deployment);
        }, () -> log.warn("Deployment disappeared before submission could be recorded: id={}", deploymentId));
    }
}
