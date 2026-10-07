package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.events.LegacyComplianceModuleDetectedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LegacyComplianceModuleScanner")
class LegacyComplianceModuleScannerTest {

    private static final String LEGACY = "0x00000000000000000000000000000000000000D1";

    private final Erc3643SuiteRepository suites = mock(Erc3643SuiteRepository.class);
    private final Erc3643LifecycleService lifecycle = mock(Erc3643LifecycleService.class);
    private final AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
    private final AssetLookupPort assets = mock(AssetLookupPort.class);
    private final EntityTaskPort tasks = mock(EntityTaskPort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID issuerId = UUID.randomUUID();
    private LegacyComplianceModuleScanner scanner;

    @BeforeEach
    void setUp() {
        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        UUID deploymentId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        suite.setAssetDeploymentId(deploymentId);
        suite.setComplianceAddress("0x00000000000000000000000000000000000000c0");
        when(suites.findAll()).thenReturn(List.of(suite));
        AssetDeployment dep = new AssetDeployment();
        dep.setId(deploymentId);
        dep.setAssetId(assetId);
        when(deployments.findById(deploymentId)).thenReturn(Optional.of(dep));
        when(assets.findById(assetId)).thenReturn(Optional.of(new AssetLookupPort.AssetInfo(
                assetId, "Bond", null, null, null, null, issuerId, "A-1", "ISSUED")));
        scanner = new LegacyComplianceModuleScanner(suites, lifecycle, deployments, assets, tasks, events, meters);
    }

    @Test
    @DisplayName("a legacy module raises one operator task on the issuer, one audit event and the gauge; CURRENT/NOT_EWPG do not")
    void legacyModuleIsRaisedOnce() {
        when(lifecycle.inspectBoundModules(suiteId)).thenReturn(List.of(
                new Erc3643LifecycleService.BoundModule(LEGACY, "EwpgComplianceModule",
                        Erc3643LifecycleService.ModuleGeneration.LEGACY),
                new Erc3643LifecycleService.BoundModule("0xd2", "EwpgComplianceModule",
                        Erc3643LifecycleService.ModuleGeneration.CURRENT),
                new Erc3643LifecycleService.BoundModule("0xd3", "CountryAllowModule",
                        Erc3643LifecycleService.ModuleGeneration.NOT_EWPG)));
        when(tasks.open(eq(issuerId), eq("COMPLIANCE_MODULE_LEGACY"), eq(LEGACY.toLowerCase()), anyString(), any()))
                .thenReturn(true);

        assertThat(scanner.scan()).isEqualTo(1);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(LegacyComplianceModuleDetectedEvent.class);
        assertThat(meters.get("registerwerk_erc3643_legacy_compliance_modules").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a module whose task is already open is counted but not re-announced")
    void alreadyOpenTaskIsNotReannounced() {
        when(lifecycle.inspectBoundModules(suiteId)).thenReturn(List.of(
                new Erc3643LifecycleService.BoundModule(LEGACY, "EwpgComplianceModule",
                        Erc3643LifecycleService.ModuleGeneration.LEGACY)));
        when(tasks.open(any(), anyString(), anyString(), anyString(), any())).thenReturn(false);

        assertThat(scanner.scan()).isEqualTo(1);

        verify(events, never()).publishEvent(any(Object.class));
        assertThat(meters.get("registerwerk_erc3643_legacy_compliance_modules").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a suite that cannot be read is skipped, not reported and does not stop the scan")
    void unreadableSuiteIsSkipped() {
        when(lifecycle.inspectBoundModules(suiteId)).thenThrow(new RuntimeException("rpc down"));

        assertThat(scanner.scan()).isZero();

        verify(tasks, never()).open(any(), anyString(), anyString(), anyString(), any());
    }
}
