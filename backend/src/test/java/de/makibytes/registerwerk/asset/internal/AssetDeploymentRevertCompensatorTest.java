package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetDeploymentRevertCompensator — the INVERSE_FLIP compensator for DEPLOYMENT_CONFIRMED")
class AssetDeploymentRevertCompensatorTest {

    @Mock private AssetDeploymentRepository repository;

    private AssetDeploymentRevertCompensator compensator;

    private final UUID deploymentId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new AssetDeploymentRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return effect("0xblock100");
    }

    private ChainEffectRecord effect(String blockHash) {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, blockHash, "0xtxhash", null,
                "asset", "DEPLOYMENT_CONFIRMED", "AssetDeployment", deploymentId, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    @DisplayName("advertises effectType DEPLOYMENT_CONFIRMED and category INVERSE_FLIP")
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("DEPLOYMENT_CONFIRMED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    @DisplayName("a CONFIRMED deployment is reverted to PENDING, contractAddress left untouched (CREATE2 is deterministic)")
    void compensateRevertsConfirmedDeployment() {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        deployment.setBlockNumber(100L);
        deployment.setBlockHash("0xblock100");
        deployment.setChainConfigId(chainConfigId);
        deployment.setDeployedByTx("0xtxhash");
        deployment.setDeployedAt(Instant.now());
        deployment.setContractAddress("0xdeterministic");
        when(repository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(deployment);
        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        assertThat(deployment.getBlockNumber()).isNull();
        assertThat(deployment.getBlockHash()).isNull();
        assertThat(deployment.getDeployedAt()).isNull();
        assertThat(deployment.getContractAddress()).isEqualTo("0xdeterministic");
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    @DisplayName("a stale same-height incarnation cannot undo a newer canonical confirmation")
    void staleSameHeightIncarnationIsNotApplicable() {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        deployment.setChainConfigId(chainConfigId);
        deployment.setDeployedByTx("0xtxhash");
        deployment.setBlockNumber(100L);
        deployment.setBlockHash("0xNEW");
        when(repository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        CompensationOutcome outcome = compensator.compensate(effect("0xOLD"));

        verify(repository, never()).save(any());
        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    @DisplayName("A to B to A does not let the stale B compensation undo the current A incarnation")
    void aToBToAStaleMiddleCompensationIsNotApplicable() {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        deployment.setChainConfigId(chainConfigId);
        deployment.setDeployedByTx("0xtxhash");
        deployment.setBlockNumber(100L);
        deployment.setBlockHash("0xA");
        when(repository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        assertThat(compensator.compensate(effect("0xB")))
                .isInstanceOf(CompensationOutcome.NotApplicable.class);
        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);

        assertThat(compensator.compensate(effect("0xA")))
                .isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
    }

    @Test
    @DisplayName("a deployment no longer CONFIRMED is NotApplicable, not re-reverted")
    void nonConfirmedDeploymentIsNotApplicable() {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        when(repository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    @DisplayName("a vanished AssetDeployment row is NotApplicable")
    void missingRowIsNotApplicable() {
        when(repository.findById(deploymentId)).thenReturn(Optional.empty());

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
