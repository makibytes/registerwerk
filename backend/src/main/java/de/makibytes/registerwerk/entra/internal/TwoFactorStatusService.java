package de.makibytes.registerwerk.entra.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.entra.api.EntraAuthMethod;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraIdentityModel;
import de.makibytes.registerwerk.entra.api.EntraUserMfaStatus;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import de.makibytes.registerwerk.entra.web.dto.TwoFactorStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "is my second factor set up?" for the signed-in customer.
 *
 * <p>Reads are throttled per user because the /security page polls while the user is away
 * registering at Microsoft's site — without a throttle, a few tabs left open would turn into a
 * steady stream of Graph calls and eventually tenant-wide throttling.
 */
@Service
public class TwoFactorStatusService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorStatusService.class);

    private final EntraDirectoryPort directory;
    private final PrincipalResolver principalResolver;
    private final AppUserRepository appUserRepository;
    private final RegisterwerkAuthProperties authProperties;
    private final RegisterwerkEntraProperties entraProperties;
    private final Cache<UUID, Long> lastForcedRefresh;

    TwoFactorStatusService(
            EntraDirectoryPort directory,
            PrincipalResolver principalResolver,
            AppUserRepository appUserRepository,
            RegisterwerkAuthProperties authProperties,
            RegisterwerkEntraProperties entraProperties) {
        this.directory = directory;
        this.principalResolver = principalResolver;
        this.appUserRepository = appUserRepository;
        this.authProperties = authProperties;
        this.entraProperties = entraProperties;
        this.lastForcedRefresh = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(10_000)
                .build();
    }

    @Transactional
    public TwoFactorStatusResponse statusFor(Authentication authentication, boolean forceRefresh) {
        if (!authProperties.isEntraEnabled()) {
            return notApplicable("Two-factor authentication is managed by Microsoft Entra ID and is "
                    + "not active in this environment.");
        }

        AppUser user = principalResolver.resolve(authentication).orElse(null);
        if (user == null || user.getAuthProvider() != UserAuthProvider.ENTRA) {
            return notApplicable("This account does not sign in through Microsoft Entra ID.");
        }

        if (isFederated(user)) {
            return new TwoFactorStatusResponse(
                    true, EntraIdentityModel.FEDERATED.name(), false, false, List.of(), null,
                    entraProperties.getMfaSetupUrl(),
                    "Your organisation manages sign-in through its own Microsoft Entra tenant. "
                    + "Manage two-factor authentication in your organisation's portal.");
        }

        if (!directory.isEnabled() || user.getEntraObjectId() == null) {
            return new TwoFactorStatusResponse(
                    true, EntraIdentityModel.WORKFORCE_MEMBER.name(), false, false, List.of(), null,
                    entraProperties.getMfaSetupUrl(),
                    "Two-factor status cannot be read in this environment. You can still manage your "
                    + "sign-in methods at Microsoft directly.");
        }

        if (forceRefresh && !allowForcedRefresh(user.getId())) {
            log.debug("Throttled forced 2FA refresh for user {}", user.getId());
        }

        EntraUserMfaStatus status = directory.getMfaStatus(user.getEntraObjectId().toString());
        cacheStatus(user, status);

        return new TwoFactorStatusResponse(
                status.applicable(),
                status.identityModel().name(),
                status.managedHere(),
                status.registered(),
                status.methods().stream().map(TwoFactorStatusService::describe).toList(),
                status.checkedAt(),
                entraProperties.getMfaSetupUrl(),
                status.message());
    }

    /**
     * Whether this user's home tenant differs from the operator's — the ground truth for "their
     * own organisation manages their MFA". Falls back to false when the token carried no
     * {@code tid}, so an unknown tenant is treated as ours and the Graph call decides.
     */
    private boolean isFederated(AppUser user) {
        UUID home = user.getEntraTenantId();
        if (home == null || entraProperties.getTenantId().isBlank()) {
            return false;
        }
        try {
            return !home.equals(UUID.fromString(entraProperties.getTenantId()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Records the outcome on the account row so the navigation banner can be rendered without a
     * Graph round-trip on every page load. Advisory only — never an authorisation input.
     */
    private void cacheStatus(AppUser user, EntraUserMfaStatus status) {
        if (!status.managedHere()) {
            return;
        }
        user.setEntraMfaCheckedAt(status.checkedAt());
        if (status.registered() && user.getEntraMfaRegisteredAt() == null) {
            user.setEntraMfaRegisteredAt(status.checkedAt());
        } else if (!status.registered()) {
            user.setEntraMfaRegisteredAt(null);
        }
        appUserRepository.save(user);
    }

    private boolean allowForcedRefresh(UUID userId) {
        long now = System.nanoTime();
        Long last = lastForcedRefresh.getIfPresent(userId);
        long minGap = TimeUnit.SECONDS.toNanos(entraProperties.getStatusRefreshThrottleSeconds());
        if (last != null && now - last < minGap) {
            return false;
        }
        lastForcedRefresh.put(userId, now);
        return true;
    }

    private TwoFactorStatusResponse notApplicable(String message) {
        return new TwoFactorStatusResponse(
                false, EntraIdentityModel.LOCAL.name(), false, false, List.of(), null,
                entraProperties.getMfaSetupUrl(), message);
    }

    private static String describe(EntraAuthMethod method) {
        String label = switch (method.type()) {
            case MICROSOFT_AUTHENTICATOR -> "Microsoft Authenticator";
            case PASSWORDLESS_PHONE_SIGN_IN -> "Microsoft Authenticator (passwordless)";
            case SOFTWARE_OATH -> "Authenticator app (one-time code)";
            case PHONE -> "Phone";
            case FIDO2 -> "Security key or passkey";
            case WINDOWS_HELLO -> "Windows Hello for Business";
            case EMAIL -> "Email";
            case TEMPORARY_ACCESS_PASS -> "Temporary Access Pass";
            case PASSWORD -> "Password";
            case UNKNOWN -> "Other method";
        };
        return method.displayName() == null || method.displayName().isBlank()
                ? label
                : label + " (" + method.displayName() + ")";
    }
}
