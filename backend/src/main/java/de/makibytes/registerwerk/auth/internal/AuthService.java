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
import java.util.function.LongConsumer;

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

    /** Pauses the request thread for the progressive login delay; replaceable in tests. */
    private LongConsumer delayer = AuthService::sleepQuietly;

    public void setDelayer(LongConsumer delayer) {
        this.delayer = delayer;
    }

    @Transactional
    public LoginResult login(String email, String rawPassword, String clientIp) {
        if (props.isEntraEnabled()) {
            throw new LoginDisabledException();
        }
        LoginAttemptLimiter.Decision decision = attemptLimiter.check(email, clientIp);
        if (decision.blocked()) {
            // Same exception as wrong credentials — a distinct "locked" message
            // would itself confirm that the account exists.
            throw new InvalidCredentialsException();
        }
        if (decision.delayMillis() > 0) {
            // Progressive delay (not a lock): applied to known and unknown e-mails alike, so a
            // distributed attack on one account slows it down but cannot lock the real user out.
            delayer.accept(decision.delayMillis());
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
        attemptLimiter.recordSuccess(email, clientIp);
        user.setLastLoginAt(Instant.now());
        users.save(user);
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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record LoginResult(
        String token, UUID userId, List<String> roles,
        String email, String name, UUID entityId, long ttlSeconds
    ) {}
}
