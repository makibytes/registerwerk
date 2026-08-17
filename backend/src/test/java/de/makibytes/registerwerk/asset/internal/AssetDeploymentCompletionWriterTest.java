package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.Chain;
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
    void stellarSubmission_addressKnownBeforeBroadcast_confirmsImmediately() {
        // Stellar: StellarAssetService only resolves the future after Horizon's synchronous
        // submitTransaction returns success (ledger already closed) — a non-null address here
        // already proves finality, unlike EVM (receipt after 1 confirmation) or Starknet
        // (UDC-precomputed address, known even before broadcast).
        UUID id = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(id);
        deployment.setChain(Chain.STELLAR);
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
    void evmSubmission_receiptSeenButOnlyOneConfirmation_staysPendingAwaitingDepthPoll() {
        // EVM's contractAddress is resolved from the deployed-address event log,
        // which only proves a receipt exists — one confirmation, not the configured depth.
        // markSubmitted must not treat that as final; AssetDeploymentService's scheduled poll
        // owns flipping this to CONFIRMED once the confirmation depth is actually met.
        UUID id = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(id);
        deployment.setChain(Chain.ETHEREUM);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        when(repository.findById(id)).thenReturn(Optional.of(deployment));
        AssetDeploymentCompletionWriter writer = new AssetDeploymentCompletionWriter(repository, events);

        writer.markSubmitted(id, UUID.randomUUID(), new TokenDeploymentResult("0xtx", "0xcontract"));

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        assertThat(deployment.getContractAddress()).isEqualTo("0xcontract");
        assertThat(deployment.getDeployedAt()).isNull();
        verify(repository).save(deployment);
        verify(events, never()).publishEvent(any(DeploymentConfirmedEvent.class));
    }

    @Test
    void starknetSubmission_udcPrecomputedAddress_staysPendingAwaitingL1Acceptance() {
        // Starknet: the address is precomputed deterministically before broadcast — its presence
        // proves nothing about whether the tx ever landed on-chain at all.
        UUID id = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(id);
        deployment.setChain(Chain.STARKNET);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        when(repository.findById(id)).thenReturn(Optional.of(deployment));
        AssetDeploymentCompletionWriter writer = new AssetDeploymentCompletionWriter(repository, events);

        writer.markSubmitted(id, UUID.randomUUID(), new TokenDeploymentResult("0xtx", "0xprecomputed"));

        assertThat(deployment.getDeploymentStatus()).isEqualTo(AssetDeployment.DeploymentStatus.PENDING);
        assertThat(deployment.getContractAddress()).isEqualTo("0xprecomputed");
        verify(events, never()).publishEvent(any(DeploymentConfirmedEvent.class));
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
