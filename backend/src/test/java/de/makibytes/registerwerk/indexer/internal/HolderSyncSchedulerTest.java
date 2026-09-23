package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.SyncConfig;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.indexer.api.HolderDataService;
import de.makibytes.registerwerk.indexer.api.UnmappedHolderIdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review phase 2 veto N3: the scheduler used to sync only the first {@code batchSize} issued
 * assets in a fixed order, so the tail was never refreshed and its corporate actions stayed
 * SNAPSHOT_BLOCKED by the freshness gate.
 */
@DisplayName("HolderSyncScheduler")
class HolderSyncSchedulerTest {

    @Test
    @DisplayName("syncs every eligible asset, beyond batchSize, and a failure does not stop the run")
    void pagesThroughAllIssuedAssets() {
        AssetLookupPort lookup = mock(AssetLookupPort.class);
        AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
        HolderDataService holderData = mock(HolderDataService.class);
        SyncConfig config = new SyncConfig();
        config.setAutoRefreshIntervalMinutes(15);
        config.setBatchSize(50);

        List<AssetLookupPort.AssetInfo> assets = new ArrayList<>();
        IntStream.range(0, 120).forEach(i -> assets.add(info(UUID.randomUUID(), "ISSUED")));
        AssetLookupPort.AssetInfo draft = info(UUID.randomUUID(), "DRAFT");
        AssetLookupPort.AssetInfo undeployed = info(UUID.randomUUID(), "ISSUED");
        assets.add(draft);
        assets.add(undeployed);
        when(lookup.findAll()).thenReturn(assets);
        when(deployments.findByAssetId(any())).thenReturn(List.of(new AssetDeployment()));
        when(deployments.findByAssetId(undeployed.id())).thenReturn(List.of());
        // A blocked asset early in the list must not end the run.
        doThrow(new UnmappedHolderIdentityException(assets.get(3).id(), List.of("0xabc")))
                .when(holderData).syncHoldersFromBlockchain(assets.get(3).id());

        new HolderSyncScheduler(lookup, deployments, holderData, config).syncAllActiveIssuances();

        verify(holderData, times(120)).syncHoldersFromBlockchain(any());
        verify(holderData).syncHoldersFromBlockchain(assets.get(119).id()); // the tail is reached
        verify(holderData, never()).syncHoldersFromBlockchain(draft.id());
        verify(holderData, never()).syncHoldersFromBlockchain(undeployed.id());
    }

    private static AssetLookupPort.AssetInfo info(UUID id, String status) {
        return new AssetLookupPort.AssetInfo(id, "A", null, null, null, null, null, null, status);
    }
}
