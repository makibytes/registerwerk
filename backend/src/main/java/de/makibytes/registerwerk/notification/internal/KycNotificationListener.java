package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycExpiringEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.notification.api.EmailPort;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Notifies an entity's company admins by email on KYC approval/rejection/expiry — previously
 * none of these fired any email; {@code kyc-approved.html} existed but was never referenced from
 * any code path.
 */
@Component
class KycNotificationListener {

    private final EmailPort emailPort;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;

    KycNotificationListener(EmailPort emailPort, AppUserRepository appUserRepository,
                             LegalEntityRepository legalEntityRepository) {
        this.emailPort = emailPort;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
    }

    @ApplicationModuleListener
    void on(KycApprovedEvent event) {
        String entityName = entityName(event.entityId());
        Object expiryDate = event.payload().get("expiryDate");
        notifyCompanyAdmins(event.entityId(), "Registerwerk: KYC verification approved", "kyc-approved", Map.of(
                "entityName", entityName,
                "expiryDate", expiryDate != null ? expiryDate : ""
        ));
    }

    @ApplicationModuleListener
    void on(KycRejectedEvent event) {
        String entityName = entityName(event.entityId());
        Object reason = event.payload().get("reason");
        notifyCompanyAdmins(event.entityId(), "Registerwerk: KYC verification rejected", "kyc-rejected", Map.of(
                "entityName", entityName,
                "reason", reason != null ? reason : ""
        ));
    }

    @ApplicationModuleListener
    void on(KycExpiringEvent event) {
        String entityName = entityName(event.entityId());
        boolean expired = "EXPIRED".equals(String.valueOf(event.payload().get("reason")));
        notifyCompanyAdmins(event.entityId(),
                expired ? "Registerwerk: KYC verification expired" : "Registerwerk: KYC verification expiring soon",
                "kyc-expiring", Map.of("entityName", entityName, "expired", expired));
    }

    private String entityName(UUID entityId) {
        return legalEntityRepository.findById(entityId).map(e -> e.getCurrentName()).orElse("your company");
    }

    private void notifyCompanyAdmins(UUID entityId, String subject, String template, Map<String, Object> baseVars) {
        appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId).stream()
                .filter(user -> user.getRoles().contains(AppUserRole.COMPANY_ADMIN))
                .forEach(admin -> {
                    Map<String, Object> vars = new java.util.HashMap<>(baseVars);
                    vars.put("adminName", admin.getFullName() != null ? admin.getFullName() : admin.getEmail());
                    emailPort.sendHtml(admin.getEmail(), subject, template, vars);
                });
    }
}
