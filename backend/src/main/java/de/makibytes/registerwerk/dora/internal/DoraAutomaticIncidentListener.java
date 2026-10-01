package de.makibytes.registerwerk.dora.internal;

import de.makibytes.registerwerk.audit.events.AuditDeadLetteredEvent;
import de.makibytes.registerwerk.dora.api.IctIncident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Opens an <b>unclassified draft</b> ICT incident for automatic triggers (6-13, T6-16 interim). The draft
 * is severity HIGH with {@code classificationPending=true}: no statutory clock starts and it cannot be
 * closed until a person classifies it through the classify endpoint (reason + criteria). Who classifies
 * and which detectors may open drafts is parked decision T6-16; only the audit dead-letter trigger is
 * wired. Chain outage / propagation failure detectors are intentionally not.
 */
@Component
class DoraAutomaticIncidentListener {

    static final String SOURCE_AUDIT_DEAD_LETTER = "AUDIT_DEAD_LETTERED";

    private static final Logger log = LoggerFactory.getLogger(DoraAutomaticIncidentListener.class);

    private final DoraService doraService;

    DoraAutomaticIncidentListener(DoraService doraService) {
        this.doraService = doraService;
    }

    @ApplicationModuleListener
    void on(AuditDeadLetteredEvent e) {
        log.error("Audit publication {} dead-lettered after {} attempts (listener={}, type={}); opening draft DORA incident",
                e.publicationId(), e.attempts(), e.listenerId(), e.eventType());
        doraService.openDraftIncident(
                "Audit trail write failed permanently (dead letter)",
                "An audit publication of type " + e.eventType() + " (listener " + e.listenerId()
                        + ") could not be written to the audit chain after " + e.attempts()
                        + " attempts and was moved to audit_event_dead_letter. The audit trail may be incomplete "
                        + "until the entry is resubmitted. Automatically opened draft: classify before relying on it.",
                IctIncident.Category.SYSTEM_OUTAGE,
                SOURCE_AUDIT_DEAD_LETTER, e.publicationId());
    }
}
