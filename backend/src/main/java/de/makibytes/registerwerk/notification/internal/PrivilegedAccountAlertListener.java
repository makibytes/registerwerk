package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.events.PrivilegedAccountChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Tells every REGISTRY_ADMIN when an account is created with, or granted, REGISTRY_ADMIN or
 * COMPLIANCE_OFFICER (6-06): one administrator minting another must not go unnoticed by the rest.
 * Always logged as a tagged alert as well, so it is visible with SMTP unconfigured.
 */
@Component
class PrivilegedAccountAlertListener {

    private static final Logger log = LoggerFactory.getLogger(PrivilegedAccountAlertListener.class);

    private final AppUserRepository users;
    private final EmailService emailService;

    PrivilegedAccountAlertListener(AppUserRepository users, EmailService emailService) {
        this.users = users;
        this.emailService = emailService;
    }

    @ApplicationModuleListener
    void on(PrivilegedAccountChangedEvent e) {
        log.warn("PRIVILEGED ACCOUNT {}: {} roles={} by={} bootstrap={}",
                e.change(), e.email(), e.roles(), e.actorId(), e.bootstrap());
        List<String> recipients = users.findEnabledWithRole(AppUserRole.REGISTRY_ADMIN).stream()
                .filter(u -> !u.getId().equals(e.userId()))
                .map(u -> u.getEmail())
                .toList();
        RuntimeException failure = null;
        for (String to : recipients) {
            try {
                emailService.sendHtmlOrThrow(to, "Registerwerk: privileged account " + e.change().toLowerCase().replace('_', ' '),
                        "email/privileged-account",
                        Map.of("email", e.email(), "change", e.change(), "roles", String.join(", ", e.roles()),
                                "bootstrap", e.bootstrap()));
            } catch (RuntimeException ex) {
                failure = failure == null ? ex : failure;
            }
        }
        if (failure != null) {
            throw failure; // 7A-04: leave the publication incomplete so it is retried
        }
    }
}
