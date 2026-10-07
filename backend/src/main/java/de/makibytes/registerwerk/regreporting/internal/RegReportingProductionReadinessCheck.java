package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.shared.ProductionMode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the draft/unvalidated regulatory-reporting prototype.
 *
 * <p>Neither NOOP nor SFTP makes this a production filing integration. Production use remains
 * blocked while official schemas, the reporting entity and routing rules, authenticated
 * authority receipts/corrections, and legal sign-off are absent.
 *
 * <p>Activated by {@code REGISTERWERK_PRODUCTION_MODE=true} (same switch the auth-module
 * readiness check uses). Outside production the warning still fires so the risk is visible.
 */
@Component
class RegReportingProductionReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(RegReportingProductionReadinessCheck.class);

    private final ReportingProperties reportingProperties;
    private final boolean productionMode;

    @Autowired
    RegReportingProductionReadinessCheck(ReportingProperties reportingProperties, Environment environment) {
        this(reportingProperties, ProductionMode.resolve(environment));
    }

    RegReportingProductionReadinessCheck(ReportingProperties reportingProperties, boolean productionMode) {
        this.reportingProperties = reportingProperties;
        this.productionMode = productionMode;
    }

    @PostConstruct
    void check() {
        String message = "REGULATORY REPORTING PROTOTYPE: draft/unvalidated MiFIR and DAC8/CARF "
                + "exports are not a production filing integration. SFTP proves byte transport "
                + "only; official schemas, reporting-entity/routing decisions, authenticated "
                + "authority receipts/corrections, and legal sign-off are required.";
        if (productionMode && reportingProperties.isPrototypeEnabled()) {
            throw new IllegalStateException(message);
        }
        if (reportingProperties.isPrototypeEnabled()) {
            log.warn("*** {} *** (prototype allowed here because production mode is off)", message);
        } else {
            log.info("Regulatory reporting prototype is disabled (default)");
        }
    }
}
