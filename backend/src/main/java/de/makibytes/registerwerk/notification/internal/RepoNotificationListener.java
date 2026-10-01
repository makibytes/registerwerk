package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.notification.api.EmailPort;
import de.makibytes.registerwerk.repo.events.RepoPartyNoticeEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tells the company administrators of both repo parties about margin calls, default notices, disputes and corporate actions. */
@Component
class RepoNotificationListener {
    private final EmailPort emailPort;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;

    RepoNotificationListener(EmailPort emailPort, AppUserRepository appUserRepository, LegalEntityRepository legalEntityRepository) {
        this.emailPort = emailPort; this.appUserRepository = appUserRepository; this.legalEntityRepository = legalEntityRepository;
    }

    @ApplicationModuleListener
    void on(RepoPartyNoticeEvent event) {
        for (UUID entityId : event.entityIds()) {
            String entityName = legalEntityRepository.findById(entityId).map(e -> e.getCurrentName()).orElse("your company");
            appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId).stream()
                    .filter(user -> user.getRoles().contains(AppUserRole.COMPANY_ADMIN))
                    .forEach(admin -> {
                        Map<String, Object> vars = new HashMap<>();
                        vars.put("adminName", admin.getFullName() != null ? admin.getFullName() : admin.getEmail());
                        vars.put("entityName", entityName);
                        vars.put("message", event.message());
                        emailPort.sendHtml(admin.getEmail(), event.subject(), "repo-notice", vars);
                    });
        }
    }
}
