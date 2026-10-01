package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import de.makibytes.registerwerk.auth.events.DefaultAdminSeededEvent;

/**
 * Bootstraps the first administrator from {@code DEFAULT_ADMIN_EMAIL} / {@code DEFAULT_ADMIN_PASSWORD}.
 *
 * <p>Create-if-absent only (6-04): the account is created when NO user holds REGISTRY_ADMIN and is
 * never modified afterwards. Previously every boot reset the password to the env value, re-enabled
 * the account and re-added REGISTRY_ADMIN, so a revoked or rotated bootstrap admin came back with
 * the long-lived env password at the next deploy. The seeded account carries
 * {@code must_change_password}; {@code ProductionReadinessCheck} fails production boots that still
 * have it after 24 hours.
 */
@Component
// Must run before any other runner (DemoDataSeeder seeds further REGISTRY_ADMINs): the seed condition is "no admin exists".
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class DefaultAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final RegisterwerkAuthProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public DefaultAdminSeeder(
            AppUserRepository users,
            PasswordEncoder encoder,
            RegisterwerkAuthProperties props,
            ApplicationEventPublisher events,
            PlatformTransactionManager txManager) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        var admin = props.getDefaultAdmin();
        if (admin.getEmail() == null || admin.getEmail().isBlank()
                || admin.getPassword() == null || admin.getPassword().isBlank()) {
            log.info("DEFAULT_ADMIN_EMAIL/DEFAULT_ADMIN_PASSWORD not set — skipping admin seed");
            return;
        }
        tx.executeWithoutResult(status -> seed(admin.getEmail(), admin.getPassword()));
    }

    private void seed(String email, String password) {
        if (users.countWithRole(AppUserRole.REGISTRY_ADMIN) > 0) {
            log.info("A REGISTRY_ADMIN already exists — default admin seed skipped, no existing account is modified");
            return;
        }
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            log.error("No REGISTRY_ADMIN exists but DEFAULT_ADMIN_EMAIL {} belongs to an existing account that is not "
                    + "modified by the seeder. Repair the roles of that account directly in the database.", email);
            return;
        }
        AppUser u = new AppUser();
        u.setEmail(email);
        u.setFullName("Registry Administrator");
        u.setPasswordHash(encoder.encode(password));
        u.setRole(AppUserRole.REGISTRY_ADMIN);
        u.setAuthProvider(UserAuthProvider.LOCAL);
        u.setEnabled(true);
        u.setMustChangePassword(true);
        AppUser saved = users.save(u);
        events.publishEvent(new DefaultAdminSeededEvent(saved.getId(), saved.getEmail()));
        log.info("Default admin user seeded: {} (must change password)", email);
    }
}
