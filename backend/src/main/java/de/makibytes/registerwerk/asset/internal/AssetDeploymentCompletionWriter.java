package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Persists asynchronous deployment outcomes inside a fresh, auditable transaction. */
@Component
public class AssetDeploymentCompletionWriter {

    private static final Logger log = LoggerFactory.getLogger(AssetDeploymentCompletionWriter.class);

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
                deployment.setDeployedAt(Instant.now());
                deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
                events.publishEvent(new DeploymentConfirmedEvent(
                        deploymentId, actorId, null, result.contractAddress(), result.txHash()));
            }
            repository.save(deployment);
        }, () -> log.warn("Deployment disappeared before submission could be recorded: id={}", deploymentId));
    }
}
