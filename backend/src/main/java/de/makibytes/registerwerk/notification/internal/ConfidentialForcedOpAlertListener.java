package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.blockchain.events.ConfidentialForcedOpOutcomeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Operator-facing alert for a confidential forced transfer / forced burn whose on-chain outcome
 * is not "executed" — a court/BaFin-ordered correction that moved nothing (insufficient encrypted
 * balance) or could not be verified. Same distinctly-tagged ERROR log convention as
 * {@link ConfidentialReconciliationAlertListener}. The decrypted amount is never logged here; it
 * lives only in the audit payload.
 */
@Component
class ConfidentialForcedOpAlertListener {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialForcedOpAlertListener.class);

    @ApplicationModuleListener
    void on(ConfidentialForcedOpOutcomeEvent event) {
        if (event.outcome() == ConfidentialForcedOpOutcomeEvent.Outcome.EXECUTED) {
            return;
        }
        log.error("CONFIDENTIAL FORCED OPERATION NOT EXECUTED: {} tx={} (txId={}) on asset={} deployment={} "
                        + "has outcome {} — the ordered correction is not reflected on-chain; review and "
                        + "re-issue if required. reason={}",
                event.methodName(), event.txHash(), event.txId(), event.assetId(), event.deploymentId(),
                event.outcome(), event.reason());
    }
}
