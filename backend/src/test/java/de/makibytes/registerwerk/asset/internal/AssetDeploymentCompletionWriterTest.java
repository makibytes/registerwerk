package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetDeploymentCompletionWriterTest {

    @Mock AssetDeploymentRepository repository;
    @Mock ApplicationEventPublisher events;

    @Test
    void successfulSubmissionPersistsAndPublishesInsideWriter() {
        UUID id = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(id);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        when(repository.findById(id)).thenReturn(Optional.of(deployment));
        AssetDeploymentCompletionWriter writer = new AssetDeploymentCompletionWriter(repository, events);

        writer.markSubmitted(id, UUID.randomUUID(), new TokenDeploymentResult("0xtx", "0xcontract"));

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(deployment.getDeployedAt()).isNotNull();
        verify(repository).save(deployment);
        verify(events).publishEvent(any(DeploymentConfirmedEvent.class));
    }

    @Test
    void lateFailureCannotOverwriteConfirmedDeployment() {
        UUID id = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(id);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        when(repository.findById(id)).thenReturn(Optional.of(deployment));
        AssetDeploymentCompletionWriter writer = new AssetDeploymentCompletionWriter(repository, events);

        writer.markFailed(id, UUID.randomUUID(), new RuntimeException("late failure"));

        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any(DeploymentFailedEvent.class));
    }
}
