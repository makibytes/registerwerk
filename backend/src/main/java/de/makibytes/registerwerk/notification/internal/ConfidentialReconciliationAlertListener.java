package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.blockchain.events.ConfidentialReconciliationCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Operator-facing alert for confidential-balance reconciliation mismatches.
 *
 * <p>Logs at ERROR rather than sending email: this repo's established pattern for this class of
 * operational-monitoring concern (cf. {@code regreporting.internal.RegReportStalenessMonitor})
 * is a distinctly-tagged log line an ops/monitoring stack (log-based alert rules) picks up, not
 * an ad hoc broadcast email to an undefined admin recipient list — no such "all REGISTRY_ADMIN
 * users" broadcast mechanism exists elsewhere in the notification module to reuse. A clean run
 * ({@code mismatchCount == 0}) is deliberately not alerted on — only genuine divergences are.
 */
@Component
class ConfidentialReconciliationAlertListener {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialReconciliationAlertListener.class);

    @ApplicationModuleListener
    void on(ConfidentialReconciliationCompletedEvent event) {
        if (event.mismatchCount() == 0) {
            return;
        }
        log.error("CONFIDENTIAL BALANCE RECONCILIATION MISMATCH: {} of {} holder(s) of asset={} (deployment={}) "
                        + "have a decrypted on-chain confidential balance that disagrees with the register's own "
                        + "eWpG §16 nominal amount — investigate immediately. details={}",
                event.mismatchCount(), event.holderCount(), event.assetId(), event.deploymentId(), event.details());
    }
}
