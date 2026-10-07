package de.makibytes.registerwerk.auth.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.AppUserSessionStateListener;
import de.makibytes.registerwerk.auth.api.EntityActivityPort;
import de.makibytes.registerwerk.auth.api.ImpersonationMode;
import de.makibytes.registerwerk.auth.api.ImpersonationSession;
import de.makibytes.registerwerk.auth.api.ImpersonationSessionRepository;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cached read side of the per-request session guard. Everything here is a small, TTL-bounded
 * lookup (default 15 s; in-process changes evict immediately) so the guard costs at most one
 * cheap query per user per TTL rather than one per request.
 */
@Component
class SessionStateService {

    record UserState(boolean enabled, Instant tokensValidAfter, UUID legalEntityId, boolean registryAdmin,
                     boolean entityTerminated, boolean mustChangePassword) {
        UserState(boolean enabled, Instant tokensValidAfter, UUID legalEntityId, boolean registryAdmin,
                  boolean entityTerminated) {
            this(enabled, tokensValidAfter, legalEntityId, registryAdmin, entityTerminated, false);
        }
    }

    record ImpersonationState(UUID actorId, UUID targetEntityId, ImpersonationMode mode, boolean active,
                              boolean targetTerminated) {}

    private final AppUserRepository users;
    private final SessionRevocationRepository revocations;
    private final ImpersonationSessionRepository impersonations;
    private final EntityActivityPort entityActivity;
    private final Cache<UUID, Optional<UserState>> userCache;
    private final Cache<String, Boolean> revokedCache;
    private final Cache<UUID, Optional<ImpersonationState>> impersonationCache;

    SessionStateService(AppUserRepository users, SessionRevocationRepository revocations,
                        ImpersonationSessionRepository impersonations, EntityActivityPort entityActivity,
                        RegisterwerkAuthProperties props) {
        this.users = users;
        this.revocations = revocations;
        this.impersonations = impersonations;
        this.entityActivity = entityActivity;
        Duration ttl = Duration.ofSeconds(props.getSessionGuardCacheSeconds());
        this.userCache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(50_000).build();
        this.revokedCache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
        this.impersonationCache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    @PostConstruct
    void registerEviction() {
        AppUserSessionStateListener.register(this::evictUser);
    }

    Optional<UserState> user(UUID userId) {
        return userCache.get(userId, id -> users.findById(id).map(this::toState));
    }

    private UserState toState(AppUser u) {
        return new UserState(u.isEnabled(), u.getTokensValidAfter(), u.getLegalEntityId(),
                u.hasRole(AppUserRole.REGISTRY_ADMIN), entityActivity.isTerminated(u.getLegalEntityId()),
                u.isMustChangePassword());
    }

    boolean isRevoked(String jti) {
        return revokedCache.get(jti, revocations::existsById);
    }

    Optional<ImpersonationState> impersonation(UUID sessionId) {
        return impersonationCache.get(sessionId, id -> impersonations.findById(id).map(this::toState));
    }

    private ImpersonationState toState(ImpersonationSession s) {
        return new ImpersonationState(s.getActorId(), s.getTargetEntityId(), s.getMode(),
                s.isActive(Instant.now()), entityActivity.isTerminated(s.getTargetEntityId()));
    }

    void evictUser(UUID userId) {
        userCache.invalidate(userId);
    }

    void markRevoked(String jti) {
        revokedCache.put(jti, Boolean.TRUE);
    }

    void evictImpersonation(UUID sessionId) {
        impersonationCache.invalidate(sessionId);
    }
}
