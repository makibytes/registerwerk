package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.events.LegacyComplianceModuleDetectedEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds tokens still bound to the legacy open-setter {@code EwpgComplianceModule} (H4). Its setters only
 * require the compliance to be bound, so any wallet can raise {@code maxBalance} or unblock a country, and
 * it has no {@code syncHolders} back-fill. The module cannot be fixed in place and the replacement changes
 * the token's enforcement, so this scan does not swap anything on its own: it makes the exposure impossible
 * to miss - an operator task on the issuer entity (once per module), an audit event, an error log and the
 * gauge {@code registerwerk_erc3643_legacy_compliance_modules} - and the operator resolves it with the
 * audited {@code POST .../compliance-modules/replace} action (bind new, configure, verify, back-fill
 * holders, only then unbind the legacy module; see {@link Erc3643LifecycleService#replaceLegacyComplianceModule}).
 * A suite whose modules cannot be read is skipped with a warning, never reported as legacy.
 */
@Component
class LegacyComplianceModuleScanner {

    static final String TASK_KIND = "COMPLIANCE_MODULE_LEGACY";

    private static final Logger log = LoggerFactory.getLogger(LegacyComplianceModuleScanner.class);

    private final Erc3643SuiteRepository suites;
    private final Erc3643LifecycleService lifecycle;
    private final AssetDeploymentRepository deployments;
    private final AssetLookupPort assets;
    private final EntityTaskPort tasks;
    private final ApplicationEventPublisher events;
    private final AtomicInteger legacyModules = new AtomicInteger();

    LegacyComplianceModuleScanner(Erc3643SuiteRepository suites, Erc3643LifecycleService lifecycle,
                                  AssetDeploymentRepository deployments, AssetLookupPort assets,
                                  EntityTaskPort tasks, ApplicationEventPublisher events, MeterRegistry meters) {
        this.suites = suites;
        this.lifecycle = lifecycle;
        this.deployments = deployments;
        this.assets = assets;
        this.tasks = tasks;
        this.events = events;
        Gauge.builder("registerwerk_erc3643_legacy_compliance_modules", legacyModules, AtomicInteger::get)
                .description("Compliance modules bound to T-REX suites that are the legacy open-setter "
                        + "EwpgComplianceModule (replace with the audited operator action)")
                .register(meters);
    }

    @SchedulerLock(name = "legacyComplianceModuleScanner", lockAtMostFor = "PT30M")
    @Scheduled(fixedDelayString = "${registerwerk.erc3643.legacy-module-scan-ms:86400000}",
            initialDelayString = "${registerwerk.erc3643.legacy-module-scan-initial-ms:180000}")
    public void scheduledScan() {
        scan();
    }

    /** @return the number of legacy modules found in this pass */
    int scan() {
        int found = 0;
        for (Erc3643Suite suite : suites.findAll()) {
            try {
                for (Erc3643LifecycleService.BoundModule module : lifecycle.inspectBoundModules(suite.getId())) {
                    if (module.generation() == Erc3643LifecycleService.ModuleGeneration.LEGACY) {
                        found++;
                        raise(suite, module.address());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Could not inspect the compliance modules of suite {}: {}", suite.getId(), e.getMessage());
            }
        }
        legacyModules.set(found);
        return found;
    }

    private void raise(Erc3643Suite suite, String moduleAddress) {
        UUID assetId = deployments.findById(suite.getAssetDeploymentId())
                .map(d -> d.getAssetId()).orElse(null);
        UUID issuerId = assetId == null ? null : assets.findById(assetId).map(AssetLookupPort.AssetInfo::issuerId).orElse(null);
        String detail = "Token compliance " + suite.getComplianceAddress() + " has the legacy open-setter "
                + "EwpgComplianceModule " + moduleAddress + " bound: any wallet can change its limits. Replace it "
                + "with POST /api/v1/assets/" + assetId + "/erc3643/" + suite.getAssetDeploymentId()
                + "/compliance-modules/replace (step-up + second approver).";
        boolean first = issuerId == null || tasks.open(issuerId, TASK_KIND, moduleAddress.toLowerCase(), detail, null);
        if (first) {
            log.error("LEGACY COMPLIANCE MODULE: suite={} compliance={} module={} is the open-setter build; replace it "
                    + "(operator action compliance-modules/replace).", suite.getId(), suite.getComplianceAddress(),
                    moduleAddress);
            Map<String, Object> details = new HashMap<>();
            details.put("complianceAddress", suite.getComplianceAddress());
            details.put("moduleAddress", moduleAddress);
            details.put("assetDeploymentId", String.valueOf(suite.getAssetDeploymentId()));
            events.publishEvent(new LegacyComplianceModuleDetectedEvent(suite.getId(), details));
        }
    }
}
