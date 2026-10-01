package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The "bootstrap administrator must rotate within 24 h" rule (6-04) and its blast radius (7A-12,
 * T7-03 interim). Crashing every replica for an organisational lapse takes customers down as well, so
 * the rule is outage-causing only while the seeded account is the SOLE usable REGISTRY_ADMIN (the
 * bootstrap state the rule was designed to force). Once another enabled admin who set their own
 * password exists, startup continues, an ERROR is logged hourly, gauge
 * {@code registerwerk_seeded_admin_default_credentials} is 1 and {@link AuthService} refuses password
 * login for the seeded account; the other admin is the recovery path.
 */
@Component
class SeededAdminPolicy {

    enum State { OK, DEGRADED, FATAL }

    private static final Logger log = LoggerFactory.getLogger(SeededAdminPolicy.class);
    static final Duration GRACE = Duration.ofHours(24);

    private final RegisterwerkAuthProperties authProps;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final de.makibytes.registerwerk.shared.ProductionMode productionMode;
    private final AtomicInteger gauge = new AtomicInteger();

    SeededAdminPolicy(RegisterwerkAuthProperties authProps, AppUserRepository users, PasswordEncoder encoder,
                      MeterRegistry registry, org.springframework.core.env.Environment environment) {
        this.productionMode = de.makibytes.registerwerk.shared.ProductionMode.of(environment);
        this.authProps = authProps;
        this.users = users;
        this.encoder = encoder;
        Gauge.builder("registerwerk_seeded_admin_default_credentials", gauge, AtomicInteger::get)
                .description("1 while the seeded admin still has default credentials past the grace period but another admin exists")
                .register(registry);
    }

    /** Reason text when the seeded account has outlived its grace with default credentials, else null. */
    String staleDefaultReason(AppUser admin) {
        if (admin == null || !admin.isEnabled()
                || admin.getCreatedAt().isAfter(Instant.now().minus(GRACE))) {
            return null;
        }
        String email = admin.getEmail();
        if (admin.isMustChangePassword()) {
            return "The seeded administrator " + email + " still has must_change_password after "
                    + GRACE.toHours() + " h. Complete a password reset for the account (or disable it once "
                    + "another REGISTRY_ADMIN exists).";
        }
        String pw = authProps.getDefaultAdmin().getPassword();
        if (pw != null && !pw.isBlank() && admin.getPasswordHash() != null
                && encoder.matches(pw, admin.getPasswordHash())) {
            return "The seeded administrator " + email + " still uses the DEFAULT_ADMIN_PASSWORD from the "
                    + "environment after " + GRACE.toHours() + " h. Rotate the password.";
        }
        return null;
    }

    State evaluate() {
        String email = authProps.getDefaultAdmin().getEmail();
        if (email == null || email.isBlank()) {
            gauge.set(0);
            return State.OK;
        }
        AppUser admin = users.findByEmailIgnoreCase(email).orElse(null);
        if (staleDefaultReason(admin) == null) {
            gauge.set(0);
            return State.OK;
        }
        if (users.countEnabledWithOwnPasswordExcluding(AppUserRole.REGISTRY_ADMIN, admin.getId()) > 0) {
            gauge.set(1);
            return State.DEGRADED;
        }
        gauge.set(0);
        return State.FATAL;
    }

    /** True when password login for {@code user} must be refused (degraded state, seeded account only). */
    boolean refusesLogin(AppUser user) {
        if (!productionMode.enabled() || user == null || user.getEmail() == null) {
            return false;
        }
        String email = authProps.getDefaultAdmin().getEmail();
        if (email == null || email.isBlank() || !email.equalsIgnoreCase(user.getEmail())) {
            return false;
        }
        return staleDefaultReason(user) != null
                && users.countEnabledWithOwnPasswordExcluding(AppUserRole.REGISTRY_ADMIN, user.getId()) > 0;
    }

    /** Hourly reminder plus gauge refresh while degraded; per replica by design (no shared state to lock). */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    void remind() {
        if (!productionMode.enabled()) {
            return;
        }
        try {
            if (evaluate() == State.DEGRADED) {
                log.error("*** SECURITY: the seeded administrator still has default credentials after "
                        + "{} h; password login for it is refused. Reset its password from another "
                        + "REGISTRY_ADMIN account. ***", GRACE.toHours());
            }
        } catch (RuntimeException e) {
            log.warn("Seeded-admin reminder failed: {}", e.toString());
        }
    }
}
