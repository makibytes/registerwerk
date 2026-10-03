package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.HolderSyncStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.indexer.api.IndexedChainTime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wave 0b H7: the snapshot gate compares the BLOCK time the indexer has processed up to with the record-date cut-off,
 * not only the wall-clock time of the last holder sync.
 */
@DisplayName("RegisterFreshnessGate: indexed block time vs the record-date cut-off (H7)")
class RegisterFreshnessGateBlockTimeTest {

    private static final LocalDate RECORD_DATE = LocalDate.of(2025, 6, 27);

    private final UUID assetId = UUID.randomUUID();
    private final AssetRepository assets = mock(AssetRepository.class);
    private final AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
    private final IndexedChainTime chainTime = mock(IndexedChainTime.class);
    private RegisterFreshnessGate gate;
    private Instant cutoff;
    private Asset asset;

    @BeforeEach
    void setUp() {
        var clock = CorporateActionTestSupport.registerClockAt(RECORD_DATE.plusDays(3));
        cutoff = clock.endOfDay(RECORD_DATE);
        gate = new RegisterFreshnessGate(assets, deployments, mock(CorporateActionRepository.class),
                new SimpleMeterRegistry(), clock, Duration.ofMinutes(30), chainTime);
        asset = new Asset();
        asset.setId(assetId);
        asset.setHolderSyncStatus(HolderSyncStatus.OK);
        when(assets.findById(assetId)).thenReturn(Optional.of(asset));
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setAssetId(assetId);
        d.setTokenDecimals(0);
        d.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        when(deployments.findByAssetId(assetId)).thenReturn(List.of(d));
    }

    private void lastHolderSync(Instant at) {
        ReflectionTestUtils.setField(asset, "lastSuccessfulHolderSyncAt", at);
    }

    @Test
    @DisplayName("the holder sync ran after the cut-off (wall clock) but the indexer has only seen the chain up to BEFORE it: refused")
    void laggingIndexerIsRefusedDespiteAFreshWallClockSync() {
        lastHolderSync(cutoff.plus(Duration.ofHours(5)));
        when(chainTime.indexedThrough(any())).thenReturn(Optional.of(cutoff.minus(Duration.ofHours(2))));

        Optional<String> reason = gate.blockedReason(assetId, RECORD_DATE);

        assertThat(reason).hasValueSatisfying(r -> assertThat(r)
                .contains("indexer has processed the chain only up to block time")
                .contains(cutoff.minus(Duration.ofHours(2)).toString()));
    }

    @Test
    @DisplayName("the indexed block time has reached the cut-off and the sync is fresh: the register may be used")
    void indexedPastTheCutoffIsAccepted() {
        lastHolderSync(cutoff.plus(Duration.ofHours(5)));
        when(chainTime.indexedThrough(any())).thenReturn(Optional.of(cutoff.plusSeconds(60)));

        assertThat(gate.blockedReason(assetId, RECORD_DATE)).isEmpty();
    }

    @Test
    @DisplayName("the indexer is past the cut-off but no holder sync ran since: still refused (both signals are required)")
    void freshChainTimeDoesNotReplaceTheHolderSync() {
        lastHolderSync(cutoff.minus(Duration.ofHours(1)));
        when(chainTime.indexedThrough(any())).thenReturn(Optional.of(cutoff.plusSeconds(60)));

        assertThat(gate.blockedReason(assetId, RECORD_DATE)).hasValueSatisfying(r -> assertThat(r)
                .contains("not yet past the end of record date"));
    }

    @Test
    @DisplayName("no block-time evidence for the chain (non-EVM indexer): falls back to the wall-clock rule, never to 'fresh'")
    void noEvidenceFallsBackToTheWallClockRule() {
        when(chainTime.indexedThrough(any())).thenReturn(Optional.empty());

        lastHolderSync(cutoff.minus(Duration.ofHours(1)));
        assertThat(gate.blockedReason(assetId, RECORD_DATE)).isPresent();

        lastHolderSync(cutoff.plus(Duration.ofHours(5)));
        assertThat(gate.blockedReason(assetId, RECORD_DATE)).isEmpty();
    }
}
