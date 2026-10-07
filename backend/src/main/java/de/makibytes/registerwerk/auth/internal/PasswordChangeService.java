package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.SessionRevocationPort;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.events.PasswordChangedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service password change for LOCAL accounts - the one call a {@code must_change_password} session
 * (restricted token, see {@link UserSessionGuardFilter}) is allowed to make. Verifies the current password,
 * stores the new hash, clears the flag, revokes the presenting token by {@code jti} and returns a fresh,
 * unrestricted session token.
 */
@Service
public class PasswordChangeService {

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 200;

    public record Changed(String token, AppUser user, long ttlSeconds) {}

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtMintingService minter;
    private final SessionRevocationPort revocation;
    private final ApplicationEventPublisher events;
    private final long ttlSeconds;

    PasswordChangeService(AppUserRepository users, PasswordEncoder encoder, JwtMintingService minter,
                          SessionRevocationPort revocation, ApplicationEventPublisher events,
                          de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.minter = minter;
        this.revocation = revocation;
        this.events = events;
        this.ttlSeconds = props.getTokenTtlSeconds();
    }

    @Transactional
    public Changed change(UUID userId, String presentedJti, Instant presentedExpiry, String currentPassword,
                          String newPassword) {
        AppUser user = users.findById(userId).orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
        if (!user.isEnabled() || user.getAuthProvider() != UserAuthProvider.LOCAL || user.getPasswordHash() == null) {
            throw new IllegalArgumentException("This account has no local password to change");
        }
        if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("The current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < MIN_LENGTH || newPassword.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The new password must be " + MIN_LENGTH + "-" + MAX_LENGTH
                    + " characters long");
        }
        if (newPassword.equals(currentPassword)) {
            throw new IllegalArgumentException("The new password must differ from the current one");
        }
        boolean forced = user.isMustChangePassword();
        user.changePassword(encoder.encode(newPassword));
        users.save(user);
        if (presentedJti != null) {
            revocation.revokeSession(presentedJti, userId,
                    presentedExpiry != null ? presentedExpiry : Instant.now().plusSeconds(ttlSeconds), "PASSWORD_CHANGED");
        }
        events.publishEvent(new PasswordChangedEvent(userId, user.getEmail(), forced));
        return new Changed(minter.mint(user), user, ttlSeconds);
    }
}
