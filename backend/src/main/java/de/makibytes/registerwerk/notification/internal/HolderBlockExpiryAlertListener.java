package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.kyc.events.HolderBlockExpiryReviewEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Tells compliance that a Sperrvermerk passed its expiry date and now awaits a confirmed lift (6-25). Always logged too. */
@Component
class HolderBlockExpiryAlertListener {

    private static final Logger log = LoggerFactory.getLogger(HolderBlockExpiryAlertListener.class);

    private final AppUserRepository users;
    private final EmailService emailService;

    HolderBlockExpiryAlertListener(AppUserRepository users, EmailService emailService) {
        this.users = users;
        this.emailService = emailService;
    }

    @ApplicationModuleListener
    void on(HolderBlockExpiryReviewEvent e) {
        log.warn("SPERRVERMERK EXPIRY REVIEW: block {} passed its expiry date and stays blocking until lifted", e.holderBlockId());
        Map<String, Object> model = Map.of(
                "blockId", e.holderBlockId().toString(),
                "blockType", String.valueOf(e.payload().getOrDefault("blockType", "")),
                "walletAddress", String.valueOf(e.payload().getOrDefault("walletAddress", "")),
                "expiresAt", String.valueOf(e.payload().getOrDefault("expiresAt", "")));
        RuntimeException failure = null;
        for (AppUserRole role : new AppUserRole[]{AppUserRole.COMPLIANCE_OFFICER, AppUserRole.REGISTRY_ADMIN}) {
            for (var user : users.findEnabledWithRole(role)) {
                try {
                    emailService.sendHtmlOrThrow(user.getEmail(), "Registerwerk: Sperrvermerk expiry needs review",
                            "email/holder-block-expiry-review", model);
                } catch (RuntimeException ex) {
                    failure = failure == null ? ex : failure;
                }
            }
        }
        if (failure != null) {
            throw failure; // 7A-04: leave the publication incomplete so it is retried
        }
    }
}
