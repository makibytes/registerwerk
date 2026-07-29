package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.notification.api.EmailPort;
import de.makibytes.registerwerk.orgidentity.events.OrgSuspendedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Notifies a suspended organization's company admins by email. */
@Component
class OrgIdentityNotificationListener {

    private final EmailPort emailPort;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;

    OrgIdentityNotificationListener(EmailPort emailPort,
                                    AppUserRepository appUserRepository,
                                    LegalEntityRepository legalEntityRepository) {
        this.emailPort = emailPort;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
    }

    @ApplicationModuleListener
    void on(OrgSuspendedEvent event) {
        if (event.legalEntityId() == null) return;
        String entityName = legalEntityRepository.findById(event.legalEntityId())
                .map(entity -> entity.getCurrentName())
                .orElse("your company");
        String reason = String.valueOf(event.payload().getOrDefault("reason", "not specified"));

        appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(event.legalEntityId()).stream()
                .filter(user -> user.getRoles().contains(AppUserRole.COMPANY_ADMIN))
                .forEach(admin -> emailPort.sendHtml(
                        admin.getEmail(),
                        "Registerwerk: organization suspended",
                        "org-suspended",
                        Map.of(
                                "adminName", admin.getFullName() != null ? admin.getFullName() : admin.getEmail(),
                                "entityName", entityName,
                                "reason", reason
                        )));
    }
}
