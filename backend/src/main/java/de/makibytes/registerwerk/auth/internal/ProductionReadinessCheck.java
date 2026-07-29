package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard that enforces production-safe configuration.
 * Set REGISTERWERK_PRODUCTION_MODE=true to activate hard checks; the warning always fires.
 */
@Component
class ProductionReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessCheck.class);

    static final String DEFAULT_DEV_SECRET = "registerwerk-dev-jwt-secret-change-in-production!!";
    private static final int MIN_DUAL_CONTROL_APPROVERS = 2;

    private final RegisterwerkAuthProperties authProps;
    private final String issuerUri;

    private final String kekProviderName;

    private final boolean stepUpAllowUnenrolled;

    private final AppUserRepository appUserRepository;

    ProductionReadinessCheck(
            RegisterwerkAuthProperties authProps,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${registerwerk.wallet.kek-provider:}") String kekProviderName,
            @Value("${registerwerk.auth.step-up.allow-unenrolled:false}") boolean stepUpAllowUnenrolled,
            AppUserRepository appUserRepository) {
        this.authProps = authProps;
        this.issuerUri = issuerUri;
        this.kekProviderName = kekProviderName;
        this.stepUpAllowUnenrolled = stepUpAllowUnenrolled;
        this.appUserRepository = appUserRepository;
    }

    @PostConstruct
    void check() {
        boolean usingDefaultSecret = DEFAULT_DEV_SECRET.equals(authProps.getDevSecret());
        boolean noIssuerUri = issuerUri == null || issuerUri.isBlank();
        boolean productionMode = "true".equalsIgnoreCase(System.getenv("REGISTERWERK_PRODUCTION_MODE"));

        if (noIssuerUri && usingDefaultSecret) {
            String message = "SECURITY: JWT_ISSUER_URI is not set and JWT_DEV_SECRET is the default value. "
                    + "This configuration MUST NOT be used in production. "
                    + "Set JWT_ISSUER_URI for OIDC mode, or override JWT_DEV_SECRET with a secure random value.";
            if (productionMode) {
                throw new IllegalStateException(message);
            }
            log.error("*** {} ***", message);
        }

        if (productionMode) {
            String walletKey = System.getenv("REGISTERWERK_WALLET_MASTER_KEY");
            if (walletKey == null || walletKey.isBlank()) {
                throw new IllegalStateException(
                        "REGISTERWERK_WALLET_MASTER_KEY must be set in production mode.");
            }
            String adminEmail = authProps.getDefaultAdmin().getEmail();
            String adminPassword = authProps.getDefaultAdmin().getPassword();
            if (adminEmail == null || adminEmail.isBlank()
                    || adminPassword == null || adminPassword.isBlank()) {
                throw new IllegalStateException(
                        "DEFAULT_ADMIN_EMAIL and DEFAULT_ADMIN_PASSWORD must be set in production mode.");
            }
            if (kekProviderName == null || kekProviderName.isBlank()
                    || "ENV_VAR".equalsIgnoreCase(kekProviderName)) {
                throw new IllegalStateException(
                        "REGISTERWERK_WALLET_KEK_PROVIDER must be set to AWS_KMS, AZURE_KEY_VAULT, " +
                        "or GCP_KMS in production mode. The EnvVarKekProvider is not safe for production.");
            }
            if (stepUpAllowUnenrolled) {
                throw new IllegalStateException(
                        "registerwerk.auth.step-up.allow-unenrolled must be false in production mode — " +
                        "it bypasses the TOTP second factor that the dual-control (Vieraugenprinzip) " +
                        "actions depend on.");
            }
            log.info("Production readiness checks passed.");
        }

        checkDualControlAvailability();
    }

    /**
     * Every {@code requireSecondApprover} 4-eyes endpoint (wallet export/delete,
     * force-burn, forced-transfer, org suspension, dApp approval, Sperrvermerk) needs a
     * SECOND, distinct, TOTP-enrolled, enabled REGISTRY_ADMIN to mint a valid dual-control
     * token — {@code ensureNotLastRegistryAdmin} (OperatorUserService) only guarantees
     * ONE. With fewer than {@value #MIN_DUAL_CONTROL_APPROVERS}, every dual-control action
     * is silently unreachable, not merely degraded.
     *
     * <p>This is a loud, always-on WARNING — never a startup failure, even in production
     * mode: a brand-new deployment legitimately starts with exactly one seeded admin who
     * has not yet enrolled TOTP (they cannot enrol before their first login), so failing
     * startup here would make that first login impossible.
     */
    private void checkDualControlAvailability() {
        long eligibleApprovers = appUserRepository.countEnabledTotpEnrolledUsersWithRole(AppUserRole.REGISTRY_ADMIN);
        if (eligibleApprovers < MIN_DUAL_CONTROL_APPROVERS) {
            log.error("*** SECURITY: only {} enabled, TOTP-enrolled REGISTRY_ADMIN(s) exist. "
                            + "Every dual-control (4-eyes) action — wallet export/delete, force-burn, "
                            + "forced-transfer, org suspension, dApp approval, Sperrvermerk create/lift — "
                            + "requires a SECOND, distinct, TOTP-enrolled REGISTRY_ADMIN and is currently "
                            + "UNREACHABLE. Enrol TOTP for a second REGISTRY_ADMIN to restore these actions. ***",
                    eligibleApprovers);
        }
    }
}
