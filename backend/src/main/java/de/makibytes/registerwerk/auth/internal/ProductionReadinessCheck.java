package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import de.makibytes.registerwerk.auth.events.SoftwareHsmAcknowledgedEvent;
import de.makibytes.registerwerk.shared.DemoOnly;
import de.makibytes.registerwerk.shared.ProductionMode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard that enforces production-safe configuration.
 * Set REGISTERWERK_PRODUCTION_MODE=true (env var, -D or config source — resolved once by
 * {@link ProductionMode}) to activate hard checks; the warning always fires.
 */
@Component
class ProductionReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessCheck.class);

    static final String DEFAULT_DEV_SECRET = "registerwerk-dev-jwt-secret-change-in-production!!";
    private static final int MIN_DUAL_CONTROL_APPROVERS = 2;
    private static final java.time.Duration SEED_GRACE = java.time.Duration.ofHours(24);

    private final RegisterwerkAuthProperties authProps;
    private final String issuerUri;

    private final String kekProviderName;

    private final boolean hsmEnabled;

    private final boolean stepUpAllowUnenrolled;

    private final AppUserRepository appUserRepository;

    private final PasswordEncoder passwordEncoder;

    // Read as raw properties rather than through RegisterwerkEntraProperties: auth must not
    // depend on the entra module (entra already depends on auth.api), and these are only ever
    // inspected for blankness.
    private final String entraTenantId;
    private final String entraClientId;
    private final String entraClientSecret;
    private final boolean entraSupportEnabled;
    private final String stepUpAuthContextId;

    private final ProductionMode productionModeBean;
    private final ListableBeanFactory beanFactory;
    private final Environment env;
    private final ApplicationEventPublisher events;
    private final SeededAdminPolicy seededAdminPolicy;
    private final AtomicInteger defaultProxiesGauge = new AtomicInteger();
    private final AtomicInteger softHsmGauge = new AtomicInteger();

    static final Set<String> WEAK_DB_PASSWORDS = Set.of("", "changeme", "registerwerk");
    static final String DEFAULT_RELAYER_KEY = "registerwerk-dev-zama-relayer-key-change-in-production";
    static final String DEFAULT_CHAINCACHE_SECRET = "registerwerk-chaincache-demo-secret-change-me";
    static final String DEFAULT_ADMIN_PASSWORD = "changeme-please";
    static final String DEMO_HSM_PIN = "123456";
    static final int MIN_JWT_SECRET_BYTES = 32;

    ProductionReadinessCheck(
            RegisterwerkAuthProperties authProps,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${registerwerk.wallet.kek-provider:}") String kekProviderName,
            @Value("${registerwerk.wallet.hsm.enabled:false}") boolean hsmEnabled,
            @Value("${registerwerk.auth.step-up.allow-unenrolled:false}") boolean stepUpAllowUnenrolled,
            @Value("${registerwerk.entra.tenant-id:}") String entraTenantId,
            @Value("${registerwerk.entra.client-id:}") String entraClientId,
            @Value("${registerwerk.entra.client-secret:}") String entraClientSecret,
            @Value("${registerwerk.entra.support-enabled:false}") boolean entraSupportEnabled,
            @Value("${registerwerk.auth.step-up.entra.auth-context-id:}") String stepUpAuthContextId,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            Environment env,
            ListableBeanFactory beanFactory,
            ApplicationEventPublisher events,
            SeededAdminPolicy seededAdminPolicy,
            MeterRegistry registry) {
        this.productionModeBean = ProductionMode.of(env);
        this.beanFactory = beanFactory;
        this.env = env;
        this.events = events;
        this.seededAdminPolicy = seededAdminPolicy;
        Gauge.builder("registerwerk_config_default_trusted_proxies", defaultProxiesGauge, AtomicInteger::get)
                .description("1 in production mode while auth.trusted-proxies is the built-in private-range default")
                .register(registry);
        Gauge.builder("registerwerk_hsm_software_token", softHsmGauge, AtomicInteger::get)
                .description("1 when production mode runs on a software (SoftHSM) token by explicit acknowledgement")
                .register(registry);
        this.authProps = authProps;
        this.passwordEncoder = passwordEncoder;
        this.issuerUri = issuerUri;
        this.kekProviderName = kekProviderName;
        this.hsmEnabled = hsmEnabled;
        this.stepUpAllowUnenrolled = stepUpAllowUnenrolled;
        this.entraTenantId = entraTenantId == null ? "" : entraTenantId.trim();
        this.entraClientId = entraClientId == null ? "" : entraClientId.trim();
        this.entraClientSecret = entraClientSecret == null ? "" : entraClientSecret.trim();
        this.entraSupportEnabled = entraSupportEnabled;
        this.stepUpAuthContextId = stepUpAuthContextId == null ? "" : stepUpAuthContextId.trim();
        this.appUserRepository = appUserRepository;
    }

    @PostConstruct
    void check() {
        boolean usingDefaultSecret = DEFAULT_DEV_SECRET.equals(authProps.getDevSecret());
        boolean noIssuerUri = issuerUri == null || issuerUri.isBlank();
        boolean productionMode = productionModeBean.enabled();

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
            checkDemoAndDefaults(noIssuerUri);
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
            if (!hsmEnabled) {
                throw new IllegalStateException(
                        "REGISTERWERK_HSM_ENABLED must be true in production mode so EVM signing keys " +
                        "remain non-exportable in PKCS#11 hardware.");
            }
            checkHsm();
            if (stepUpAllowUnenrolled) {
                throw new IllegalStateException(
                        "registerwerk.auth.step-up.allow-unenrolled must be false in production mode — " +
                        "it bypasses the TOTP second factor that the dual-control (Vieraugenprinzip) " +
                        "actions depend on.");
            }
            if (!noIssuerUri && authProps.getAudience().isBlank()) {
                throw new IllegalStateException(
                        "JWT_AUDIENCE is required in production when JWT_ISSUER_URI is set.");
            }
            checkEntraConfiguration(noIssuerUri);
            checkSeededAdminPasswordRotated(adminEmail);
            log.info("Production readiness checks passed.");
        }

        checkDualControlAvailability();
    }

    /**
     * The bootstrap administrator is created once (see {@code DefaultAdminSeeder}). 24 hours later
     * production must no longer run with that password. Fatal only while the seeded account is the
     * sole usable admin; otherwise degraded (7A-12), see {@link SeededAdminPolicy}.
     */
    private void checkSeededAdminPasswordRotated(String adminEmail) {
        SeededAdminPolicy.State state = seededAdminPolicy.evaluate();
        if (state == SeededAdminPolicy.State.FATAL) {
            String reason = appUserRepository.findByEmailIgnoreCase(adminEmail)
                    .map(seededAdminPolicy::staleDefaultReason).orElse("Rotate the seeded administrator password.");
            throw new IllegalStateException(reason);
        }
        if (state == SeededAdminPolicy.State.DEGRADED) {
            log.error("*** SECURITY: the seeded administrator {} still has default credentials after {} h. "
                    + "Startup continues because another REGISTRY_ADMIN exists; password login for the seeded "
                    + "account is refused until its password is reset. ***", adminEmail, SeededAdminPolicy.GRACE.toHours());
        }
    }

    /** Demo flags, weak/default secrets and CORS/proxy hygiene (7A-01, 7A-02, 7B-08). */
    private void checkDemoAndDefaults(boolean noIssuerUri) {
        if (Boolean.parseBoolean(env.getProperty("registerwerk.seed-demo-data", "false"))) {
            throw new IllegalStateException("SEED_DEMO_DATA (registerwerk.seed-demo-data) must be false in production "
                    + "mode — it seeds known-credential accounts with a published TOTP secret.");
        }
        if (Boolean.parseBoolean(env.getProperty("registerwerk.trading.demo-instant-settlement", "false"))) {
            throw new IllegalStateException("registerwerk.trading.demo-instant-settlement must be false in production mode.");
        }
        String[] demoBeans = beanFactory.getBeanNamesForType(DemoOnly.class, true, false);
        if (demoBeans.length > 0) {
            throw new IllegalStateException("Demo seeder beans are registered in production mode ("
                    + String.join(", ", demoBeans) + "). Unset SEED_DEMO_DATA.");
        }
        String dbPassword = env.getProperty("spring.datasource.password", "");
        if (WEAK_DB_PASSWORDS.contains(dbPassword.trim().toLowerCase())) {
            throw new IllegalStateException("DB_PASSWORD is blank or a known default (changeme/registerwerk); set a "
                    + "strong database password in production mode.");
        }
        String devSecret = authProps.getDevSecret();
        if (noIssuerUri && (devSecret == null
                || devSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES)) {
            throw new IllegalStateException("JWT_DEV_SECRET must be at least " + MIN_JWT_SECRET_BYTES
                    + " bytes in production mode (it is the HS256 key for session, step-up and dual-control tokens).");
        }
        if (allOriginsLocal(env.getProperty("registerwerk.cors.allowed-origins", ""))) {
            throw new IllegalStateException("REGISTERWERK_CORS_ALLOWED_ORIGINS contains only localhost origins; set the "
                    + "real frontend origins in production mode.");
        }
        String proxies = env.getProperty("REGISTERWERK_AUTH_TRUSTED_PROXIES", "");
        boolean defaultProxies = proxies.isBlank();
        defaultProxiesGauge.set(defaultProxies ? 1 : 0);
        if (defaultProxies) {
            log.error("*** SECURITY: REGISTERWERK_AUTH_TRUSTED_PROXIES is not set; X-Forwarded-For is trusted from every "
                    + "private range, so any host in the network can pick its own login-throttle bucket. ***");
        }
        String adminPassword = authProps.getDefaultAdmin().getPassword();
        if (DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
            throw new IllegalStateException("DEFAULT_ADMIN_PASSWORD is the published example value; set a unique password.");
        }
        if (DEFAULT_RELAYER_KEY.equals(env.getProperty("registerwerk.zama.relayer-api-key", ""))) {
            throw new IllegalStateException("ZAMA_RELAYER_API_KEY is the published default; set a unique key.");
        }
        if (DEFAULT_CHAINCACHE_SECRET.equals(env.getProperty("registerwerk.chaincache.jwt-secret", ""))) {
            throw new IllegalStateException("CHAINCACHE_JWT_SECRET is the published default; set a unique secret.");
        }
    }

    static boolean allOriginsLocal(String origins) {
        var entries = Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (entries.isEmpty()) {
            return true;
        }
        return entries.stream().allMatch(o -> {
            try {
                String host = URI.create(o).getHost();
                return host == null || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            } catch (IllegalArgumentException e) {
                return true;
            }
        });
    }

    private void checkHsm() {
        String profile = env.getProperty("registerwerk.wallet.hsm.profile", "SOFTHSM");
        boolean software = "SOFTHSM".equalsIgnoreCase(profile.trim());
        boolean allowed = Boolean.parseBoolean(env.getProperty("registerwerk.wallet.hsm.allow-software-token", "false"));
        if (software && !allowed) {
            throw new IllegalStateException("REGISTERWERK_HSM_PROFILE=SOFTHSM is a software token and is refused in "
                    + "production mode. Use a hardware/network HSM profile, or set "
                    + "registerwerk.wallet.hsm.allow-software-token=true to acknowledge it explicitly.");
        }
        if (DEMO_HSM_PIN.equals(env.getProperty("registerwerk.wallet.hsm.pin", "").trim())) {
            throw new IllegalStateException("The HSM PIN is the published demo PIN; set REGISTERWERK_HSM_PIN.");
        }
        if (software) {
            softHsmGauge.set(1);
            log.error("*** SECURITY: production mode is running on a SOFTWARE HSM token by explicit acknowledgement "
                    + "(registerwerk.wallet.hsm.allow-software-token=true). Keys are not hardware protected. ***");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void acknowledgeSoftwareHsm() {
        if (softHsmGauge.get() == 1) {
            events.publishEvent(new SoftwareHsmAcknowledgedEvent(env.getProperty("registerwerk.wallet.hsm.profile", "SOFTHSM")));
        }
    }

    /**
     * Guards the ways an Entra deployment can look configured while being silently broken or
     * silently insecure.
     *
     * <p>Each of these has a failure mode that is hard to diagnose from the outside:
     * <ul>
     *   <li><strong>No issuer URI</strong> — Entra sign-in is on but no token from it can be
     *       validated, so every customer request is rejected.</li>
     *   <li><strong>No audience</strong> — a token Entra issued for any other application in the
     *       same tenant is accepted here as a Registerwerk session. Silent, and severe.</li>
     *   <li><strong>No tenant id</strong> — federated users cannot be told apart from local ones,
     *       so the support console would attempt Graph calls against another tenant's principals.</li>
     *   <li><strong>No step-up authentication context</strong> — all 77 step-up-protected
     *       endpoints fail closed at the first call rather than at startup.</li>
     *   <li><strong>Graph enabled without credentials</strong> — the 2FA status page and the
     *       whole support console fail at the moment an operator needs them, mid-incident.</li>
     * </ul>
     */
    private void checkEntraConfiguration(boolean noIssuerUri) {
        if (authProps.isEntraEnabled()) {
            if (noIssuerUri) {
                throw new IllegalStateException(
                        "ENTRA_ENABLED=true but JWT_ISSUER_URI is blank — no Entra-issued token "
                        + "could be validated.");
            }
            if (authProps.getAudience().isBlank()) {
                throw new IllegalStateException(
                        "JWT_AUDIENCE must be set when ENTRA_ENABLED=true. Without it, an access "
                        + "token issued to any other application in the same tenant is accepted "
                        + "by this API.");
            }
            if (entraTenantId.isBlank()) {
                throw new IllegalStateException(
                        "ENTRA_TENANT_ID must be set when ENTRA_ENABLED=true — it is how a "
                        + "federated customer identity is told apart from one in our own tenant.");
            }
            if (stepUpAuthContextId.isBlank()) {
                throw new IllegalStateException(
                        "ENTRA_STEPUP_AUTH_CONTEXT_ID must be set when ENTRA_ENABLED=true. Every "
                        + "step-up-protected endpoint (forced transfers, key export, Sperrvermerk, "
                        + "…) fails closed without it.");
            }
        }

        if (entraSupportEnabled && (entraClientId.isBlank() || entraClientSecret.isBlank())) {
            throw new IllegalStateException(
                    "ENTRA_SUPPORT_ENABLED=true requires ENTRA_CLIENT_ID and ENTRA_CLIENT_SECRET "
                    + "for app-only Microsoft Graph access.");
        }
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
