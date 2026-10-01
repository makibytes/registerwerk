package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.dora.events.IctIncidentDeadlineBreachedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Emails every registry administrator when a DORA incident reporting deadline is missed (6-13). Always logged too. */
@Component
class DoraDeadlineAlertListener {

    private static final Logger log = LoggerFactory.getLogger(DoraDeadlineAlertListener.class);

    private final AppUserRepository users;
    private final EmailService emailService;

    DoraDeadlineAlertListener(AppUserRepository users, EmailService emailService) {
        this.users = users;
        this.emailService = emailService;
    }

    @ApplicationModuleListener
    void on(IctIncidentDeadlineBreachedEvent e) {
        log.error("DORA DEADLINE ALERT: incident {} missed {} (deadline {})", e.incidentId(), e.breachType(), e.deadline());
        for (var admin : users.findEnabledWithRole(AppUserRole.REGISTRY_ADMIN)) {
            emailService.sendHtml(admin.getEmail(), "Registerwerk: DORA reporting deadline missed",
                    "email/dora-deadline-breach",
                    Map.of("incidentId", e.incidentId().toString(), "title", String.valueOf(e.title()),
                            "breachType", e.breachType(),
                            "deadline", e.deadline() != null ? e.deadline().toString() : ""));
        }
    }
}
