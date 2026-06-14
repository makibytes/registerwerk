package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.shared.InvalidCredentialsException;
import de.makibytes.registerwerk.shared.LoginDisabledException;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    /**
     * Constant BCrypt hash of a random value, used to equalize response timing
     * when the email does not exist — otherwise the missing hash comparison
     * makes user enumeration possible via response-time measurement.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtMintingService minter;
    private final RegisterwerkAuthProperties props;
    private final LoginAttemptLimiter attemptLimiter;

    public AuthService(
            AppUserRepository users,
            PasswordEncoder encoder,
            JwtMintingService minter,
            RegisterwerkAuthProperties props,
            LoginAttemptLimiter attemptLimiter) {
        this.users = users;
        this.encoder = encoder;
        this.minter = minter;
        this.props = props;
        this.attemptLimiter = attemptLimiter;
    }

    @Transactional
    public LoginResult login(String email, String rawPassword) {
        if (props.isEntraEnabled()) {
            throw new LoginDisabledException();
        }
        if (attemptLimiter.isBlocked(email)) {
            // Same exception as wrong credentials — a distinct "locked" message
            // would itself confirm that the account exists.
            throw new InvalidCredentialsException();
        }
        Optional<AppUser> candidate = users.findByEmailIgnoreCase(email)
            .filter(AppUser::isEnabled)
            .filter(found -> found.getAuthProvider() == UserAuthProvider.LOCAL);

        if (candidate.isEmpty()) {
            // Burn a comparable amount of CPU so unknown emails are not
            // distinguishable from wrong passwords by timing.
            encoder.matches(rawPassword, DUMMY_HASH);
            attemptLimiter.recordFailure(email);
            throw new InvalidCredentialsException();
        }
        AppUser user = candidate.get();
        if (user.getPasswordHash() == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            attemptLimiter.recordFailure(email);
            throw new InvalidCredentialsException();
        }
        attemptLimiter.recordSuccess(email);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        String token = minter.mint(user);
        return new LoginResult(
            token,
            user.getId(),
            user.getRoles().stream().map(Enum::name).toList(),
            props.getTokenTtlSeconds()
        );
    }

    public record LoginResult(String token, UUID userId, List<String> roles, long ttlSeconds) {}
}
