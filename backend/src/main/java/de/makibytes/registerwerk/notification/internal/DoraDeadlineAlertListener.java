package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.dora.events.IctIncidentDeadlineBreachedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import de.makibytes.registerwerk.shared.ProductionMode;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Emails every registry administrator when a DORA incident reporting deadline is missed (6-13). Always logged too. */
@Component
class DoraDeadlineAlertListener {

    private static final Logger log = LoggerFactory.getLogger(DoraDeadlineAlertListener.class);

    private final AppUserRepository users;
    private final EmailService emailService;
    private final Environment environment;

    DoraDeadlineAlertListener(AppUserRepository users, EmailService emailService, Environment environment) {
        this.users = users;
        this.emailService = emailService;
        this.environment = environment;
    }

    @ApplicationModuleListener
    void on(IctIncidentDeadlineBreachedEvent e) {
        log.error("DORA DEADLINE ALERT: incident {} missed {} (deadline {})", e.incidentId(), e.breachType(), e.deadline());
        RuntimeException failure = null;
        for (var admin : users.findEnabledWithRole(AppUserRole.REGISTRY_ADMIN)) {
            try {
                emailService.sendHtmlOrThrow(admin.getEmail(), "Registerwerk: DORA reporting deadline missed",
                        "email/dora-deadline-breach",
                        Map.of("incidentId", e.incidentId().toString(), "title", String.valueOf(e.title()),
                                "breachType", e.breachType(),
                                "deadline", e.deadline() != null ? e.deadline().toString() : ""));
            } catch (RuntimeException ex) {
                failure = failure == null ? ex : failure;
            }
        }
        if (failure != null) {
            if (!ProductionMode.resolve(environment)) {
                // Demo / local: SMTP is typically the placeholder host, so rethrowing would leave the
                // publication incomplete and re-log an ERROR stack trace on every boot. The alert is
                // already in the log above; one WARN line is enough outside production.
                log.warn("DORA deadline alert for incident {} not emailed (non-production, SMTP delivery failed: {})",
                        e.incidentId(), rootMessage(failure));
                return;
            }
            throw failure; // 7A-04: leave the publication incomplete so it is retried
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
