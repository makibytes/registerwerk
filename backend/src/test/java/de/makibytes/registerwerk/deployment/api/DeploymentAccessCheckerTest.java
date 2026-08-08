package de.makibytes.registerwerk.deployment.api;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentAccessCheckerTest {

    private final AssetDeploymentRepository repository = mock(AssetDeploymentRepository.class);
    private final DeploymentAccessChecker checker = new DeploymentAccessChecker(repository);

    @Test
    void onlyAcceptsDeploymentBoundToRequestedAsset() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(repository.findByIdAndAssetId(deploymentId, assetId))
                .thenReturn(Optional.of(new AssetDeployment()));

        assertThat(checker.belongsToAsset(deploymentId, assetId)).isTrue();
        assertThat(checker.belongsToAsset(deploymentId, UUID.randomUUID())).isFalse();
        assertThat(checker.belongsToAsset(null, assetId)).isFalse();
    }
}
