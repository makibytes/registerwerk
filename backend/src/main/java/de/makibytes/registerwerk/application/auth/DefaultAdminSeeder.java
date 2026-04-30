package de.makibytes.registerwerk.application.auth;

import de.makibytes.registerwerk.config.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.domain.entity.AppUser;
import de.makibytes.registerwerk.domain.enums.AppUserRole;
import de.makibytes.registerwerk.domain.enums.UserAuthProvider;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DefaultAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final RegisterwerkAuthProperties props;

    public DefaultAdminSeeder(
            AppUserRepository users,
            PasswordEncoder encoder,
            RegisterwerkAuthProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        var admin = props.getDefaultAdmin();
        if (admin.getEmail() == null || admin.getEmail().isBlank()
                || admin.getPassword() == null || admin.getPassword().isBlank()) {
            log.info("DEFAULT_ADMIN_EMAIL/DEFAULT_ADMIN_PASSWORD not set — skipping admin seed");
            return;
        }
        users.findByEmailIgnoreCase(admin.getEmail()).ifPresentOrElse(
            existing -> {
                existing.setPasswordHash(encoder.encode(admin.getPassword()));
                existing.setEnabled(true);
                existing.setAuthProvider(UserAuthProvider.LOCAL);
                existing.setRole(AppUserRole.REGISTRY_ADMIN);
                users.save(existing);
                log.info("Default admin user refreshed: {}", admin.getEmail());
            },
            () -> {
                AppUser u = new AppUser();
                u.setEmail(admin.getEmail());
                u.setFullName("Registry Administrator");
                u.setPasswordHash(encoder.encode(admin.getPassword()));
                u.setRole(AppUserRole.REGISTRY_ADMIN);
                u.setAuthProvider(UserAuthProvider.LOCAL);
                u.setEnabled(true);
                users.save(u);
                log.info("Default admin user seeded: {}", admin.getEmail());
            }
        );
    }
}
