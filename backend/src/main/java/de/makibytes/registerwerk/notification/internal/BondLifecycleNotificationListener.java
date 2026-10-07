package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.deployment.api.BondLifecycleTransitionEvent;
import de.makibytes.registerwerk.deployment.api.CouponLifecycleTransitionEvent;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.notification.api.EmailPort;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 9A-04R: tells the issuing entity's company admins when the system moves its bond to OVERDUE / DEFAULTED or flags a
 * coupon OVERDUE / MISSED. Before, these status changes were log-only: the issuer learned of a default from its
 * investors. MATURED / CALLED are routine and not mailed.
 */
@Component
class BondLifecycleNotificationListener {

    private final EmailPort emailPort;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final AssetRepository assetRepository;

    BondLifecycleNotificationListener(EmailPort emailPort, AppUserRepository appUserRepository,
                                      LegalEntityRepository legalEntityRepository, AssetRepository assetRepository) {
        this.emailPort = emailPort;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.assetRepository = assetRepository;
    }

    @ApplicationModuleListener
    void on(BondLifecycleTransitionEvent event) {
        if (event.to() != BondStatus.OVERDUE && event.to() != BondStatus.DEFAULTED) {
            return;
        }
        notifyIssuerAdmins(event.assetId(), "Registerwerk: bond payment " + event.to().name().toLowerCase(),
                "Bond " + event.to().name(), event.to().name(), event.cause());
    }

    @ApplicationModuleListener
    void on(CouponLifecycleTransitionEvent event) {
        notifyIssuerAdmins(event.assetId(), "Registerwerk: coupon payment " + event.to().name().toLowerCase(),
                "Coupon " + event.to().name(), event.to().name(), event.cause());
    }

    private void notifyIssuerAdmins(UUID assetId, String subject, String headline, String status, String cause) {
        Asset asset = assetRepository.findById(assetId).orElse(null);
        if (asset == null || asset.getIssuerId() == null) {
            return;
        }
        UUID issuerId = asset.getIssuerId();
        String entityName = legalEntityRepository.findById(issuerId).map(e -> e.getCurrentName()).orElse("your company");
        appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(issuerId).stream()
                .filter(user -> user.getRoles().contains(AppUserRole.COMPANY_ADMIN))
                .forEach(admin -> {
                    Map<String, Object> vars = new HashMap<>();
                    vars.put("adminName", admin.getFullName() != null ? admin.getFullName() : admin.getEmail());
                    vars.put("entityName", entityName);
                    vars.put("assetName", asset.getName());
                    vars.put("headline", headline);
                    vars.put("status", status);
                    vars.put("cause", cause != null ? cause : "");
                    emailPort.sendHtml(admin.getEmail(), subject, "bond-lifecycle-status", vars);
                });
    }
}
