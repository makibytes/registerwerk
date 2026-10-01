package de.makibytes.registerwerk.auth.api;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppUser session revocation bump")
class AppUserSessionBumpTest {

    private AppUser persisted() {
        AppUser u = new AppUser();
        u.setRoles(Set.of(AppUserRole.REGISTRY_ADMIN));
        u.setPasswordHash("hash");
        u.setId(UUID.randomUUID());
        return u;
    }

    @Test
    @DisplayName("a new (unpersisted) account is not revoked by initialisation")
    void newAccountNotBumped() {
        AppUser u = new AppUser();
        u.setRoles(Set.of(AppUserRole.TRADER));
        u.setEnabled(false);
        assertThat(u.getTokensValidAfter()).isNull();
    }

    @Test
    @DisplayName("disable, role change, entity change and password change bump; no-op changes do not")
    void bumps() {
        AppUser u = persisted();
        u.setRoles(Set.of(AppUserRole.REGISTRY_ADMIN));
        u.setEnabled(true);
        u.setPasswordHash("hash");
        assertThat(u.getTokensValidAfter()).isNull();

        u.setEnabled(false);
        assertThat(u.getTokensValidAfter()).isNotNull();

        for (Runnable change : new Runnable[] {
                () -> u.setRoles(Set.of(AppUserRole.AUDIT)),
                () -> u.setLegalEntityId(UUID.randomUUID()),
                () -> u.setPasswordHash("other") }) {
            ReflectionTestUtils.setField(u, "tokensValidAfter", null);
            change.run();
            assertThat(u.getTokensValidAfter()).isNotNull();
        }
    }
}
