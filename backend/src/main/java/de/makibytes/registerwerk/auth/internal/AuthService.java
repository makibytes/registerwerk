package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.shared.InvalidCredentialsException;
import de.makibytes.registerwerk.shared.LoginDisabledException;
import de.makibytes.registerwerk.shared.LoginThrottledException;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

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
    private final SeededAdminPolicy seededAdminPolicy;

    /** Test/legacy constructor: no seeded-admin login restriction. */
    public AuthService(
            AppUserRepository users,
            PasswordEncoder encoder,
            JwtMintingService minter,
            RegisterwerkAuthProperties props,
            LoginAttemptLimiter attemptLimiter) {
        this(users, encoder, minter, props, attemptLimiter, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    AuthService(
            AppUserRepository users,
            PasswordEncoder encoder,
            JwtMintingService minter,
            RegisterwerkAuthProperties props,
            LoginAttemptLimiter attemptLimiter,
            SeededAdminPolicy seededAdminPolicy) {
        this.seededAdminPolicy = seededAdminPolicy;
        this.users = users;
        this.encoder = encoder;
        this.minter = minter;
        this.props = props;
        this.attemptLimiter = attemptLimiter;
    }

    /**
     * Deliberately not {@code @Transactional}: a transaction would pin a pooled connection through the BCrypt
     * work (~100 ms of CPU) and the second connection {@code recordFailure} needs, so a flood of failing
     * logins larger than the pool starved it (C4). Every database step here - throttle check, user lookup,
     * counter update, last-login stamp - is its own short statement or transaction. The throttle never
     * sleeps: a caller who must wait is answered with 429 and {@code Retry-After}.
     */
    public LoginResult login(String email, String rawPassword, String clientIp) {
        if (props.isEntraEnabled()) {
            throw new LoginDisabledException();
        }
        LoginAttemptLimiter.Decision decision = attemptLimiter.check(email, clientIp);
        if (decision.blocked()) {
            // Keyed on e-mail and client address only, never on whether the account exists, so the same
            // answer covers known and unknown accounts and confirms nothing.
            throw new LoginThrottledException(decision.retryAfterSeconds());
        }
        Optional<AppUser> candidate = users.findByEmailIgnoreCase(email)
            .filter(AppUser::isEnabled)
            .filter(found -> found.getAuthProvider() == UserAuthProvider.LOCAL);

        if (candidate.isEmpty()) {
            // Burn a comparable amount of CPU so unknown emails are not
            // distinguishable from wrong passwords by timing.
            encoder.matches(rawPassword, DUMMY_HASH);
            attemptLimiter.recordFailure(email, clientIp);
            throw new InvalidCredentialsException();
        }
        AppUser user = candidate.get();
        if (user.getPasswordHash() == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            attemptLimiter.recordFailure(email, clientIp);
            throw new InvalidCredentialsException();
        }
        if (seededAdminPolicy != null && seededAdminPolicy.refusesLogin(user)) {
            // 7A-12: the unrotated bootstrap account may not log in while another admin can recover it.
            log.warn("Password login refused for the seeded administrator: default credentials past the grace period");
            attemptLimiter.recordFailure(email, clientIp);
            throw new InvalidCredentialsException();
        }
        attemptLimiter.recordSuccess(email, clientIp);
        Instant now = Instant.now();
        users.touchLastLogin(user.getId(), now);
        user.setLastLoginAt(now);
        String token = minter.mint(user);
        return new LoginResult(
            token,
            user.getId(),
            user.getRoles().stream().map(Enum::name).toList(),
            user.getEmail(),
            user.getFullName(),
            user.getLegalEntityId(),
            props.getTokenTtlSeconds()
        );
    }

    public record LoginResult(
        String token, UUID userId, List<String> roles,
        String email, String name, UUID entityId, long ttlSeconds
    ) {}
}
