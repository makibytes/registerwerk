package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetApprovedEvent;
import de.makibytes.registerwerk.asset.events.AssetRejectedEvent;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.notification.api.EmailPort;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Notifies an asset's issuing entity's company admins by email on approval/rejection —
 *  previously neither event fired any email at all. */
@Component
class AssetLifecycleNotificationListener {

    private final EmailPort emailPort;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final AssetRepository assetRepository;

    AssetLifecycleNotificationListener(EmailPort emailPort, AppUserRepository appUserRepository,
                                        LegalEntityRepository legalEntityRepository, AssetRepository assetRepository) {
        this.emailPort = emailPort;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.assetRepository = assetRepository;
    }

    @ApplicationModuleListener
    void on(AssetApprovedEvent event) {
        Asset asset = assetRepository.findById(event.assetId()).orElse(null);
        if (asset == null) return;
        notifyIssuerAdmins(asset, "Registerwerk: asset approved", "asset-approved", Map.of("assetName", asset.getName()));
    }

    @ApplicationModuleListener
    void on(AssetRejectedEvent event) {
        Asset asset = assetRepository.findById(event.assetId()).orElse(null);
        if (asset == null) return;
        notifyIssuerAdmins(asset, "Registerwerk: asset rejected", "asset-rejected", Map.of(
                "assetName", asset.getName(),
                "reason", event.reason() != null ? event.reason() : ""
        ));
    }

    private void notifyIssuerAdmins(Asset asset, String subject, String template, Map<String, Object> baseVars) {
        UUID issuerId = asset.getIssuerId();
        if (issuerId == null) return;
        String entityName = legalEntityRepository.findById(issuerId).map(e -> e.getCurrentName()).orElse("your company");
        appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(issuerId).stream()
                .filter(user -> user.getRoles().contains(AppUserRole.COMPANY_ADMIN))
                .forEach(admin -> {
                    Map<String, Object> vars = new java.util.HashMap<>(baseVars);
                    vars.put("adminName", admin.getFullName() != null ? admin.getFullName() : admin.getEmail());
                    vars.put("entityName", entityName);
                    emailPort.sendHtml(admin.getEmail(), subject, template, vars);
                });
    }
}
