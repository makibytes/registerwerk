package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code DEPLOYMENT_CONFIRMED} — undoes an {@link AssetDeployment}
 * marked CONFIRMED whose block was later retracted. Discovered by {@code CompensationDispatcher}
 * via {@link ChainEffectCompensator} collection injection.
 *
 * <p>Deliberately talks to {@link AssetDeploymentRepository} directly rather than
 * {@code AssetDeploymentCompletionWriter} or {@code AssetDeploymentService} — the same reasoning as
 * {@code blockchain.internal.tx.BlockchainTxRevertCompensator}: {@code AssetDeploymentService}
 * depends on {@code ChainEffectRecorder} (to journal {@code DEPLOYMENT_CONFIRMED} in the first
 * place), whose implementation transitively depends on every registered compensator including this
 * one — routing the reversal back through that service would close the same Spring circular-bean
 * cycle discovered building the blockchain module's compensator.
 *
 * <p>{@code contractAddress} is deliberately left untouched on revert: {@code AssetTokenFactory}
 * uses CREATE2, so the address is deterministic and stays correct even if the confirming
 * transaction is re-mined in a different block — it asserts nothing false by remaining set on a
 * PENDING row.
 */
@Component
class AssetDeploymentRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "DEPLOYMENT_CONFIRMED";

    private static final Logger log = LoggerFactory.getLogger(AssetDeploymentRevertCompensator.class);

    private final AssetDeploymentRepository repository;

    AssetDeploymentRevertCompensator(AssetDeploymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID deploymentId = effect.entityId();
        AssetDeployment deployment = repository.findById(deploymentId).orElse(null);
        if (deployment == null) {
            return new CompensationOutcome.NotApplicable("AssetDeployment " + deploymentId + " no longer exists");
        }
        if (deployment.getDeploymentStatus() != AssetDeployment.DeploymentStatus.CONFIRMED) {
            return new CompensationOutcome.NotApplicable("AssetDeployment " + deploymentId
                    + " is no longer CONFIRMED (status=" + deployment.getDeploymentStatus() + ")");
        }

        log.error("AssetDeployment id={} was CONFIRMED at block={} but that block was retracted by a reorg "
                        + "— reverting to PENDING for re-verification. Compensation runs at any reorg depth; "
                        + "if this retraction reached an already-FINALIZED block, that is additionally a "
                        + "consensus failure deeper than the configured confirmation policy guarantees.",
                deploymentId, deployment.getBlockNumber());
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        deployment.setBlockHash(null);
        deployment.setBlockNumber(null);
        deployment.setDeployedAt(null);
        repository.save(deployment);

        return new CompensationOutcome.Compensated(
                "Reverted asset_deployment " + deploymentId + " to PENDING after retraction");
    }
}
