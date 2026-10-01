package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.ImpersonationSession;
import de.makibytes.registerwerk.auth.api.ImpersonationSessionRepository;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.SessionRevocationPort;
import de.makibytes.registerwerk.auth.events.ImpersonationEndedEvent;
import de.makibytes.registerwerk.auth.events.ImpersonationHandoffExchangedEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handoff-code exchange and session end for impersonation (6-31/6-32). The code is 256-bit
 * random, stored only as SHA-256, valid for seconds and consumed by one atomic UPDATE, so a
 * replay (or a race) yields exactly one session cookie; any later presentation ends the session.
 */
@Service
public class ImpersonationSessionService {

    public record Exchange(String token, ImpersonationSession session, AppUser actor, long ttlSeconds) {}

    private final ImpersonationSessionRepository sessions;
    private final AppUserRepository users;
    private final JwtMintingService minter;
    private final SessionRevocationPort revocation;
    private final SessionStateService state;
    private final ApplicationEventPublisher events;

    ImpersonationSessionService(ImpersonationSessionRepository sessions, AppUserRepository users,
                                JwtMintingService minter, SessionRevocationPort revocation,
                                SessionStateService state, ApplicationEventPublisher events,
                                RegisterwerkAuthProperties props) {
        this.sessions = sessions;
        this.users = users;
        this.minter = minter;
        this.revocation = revocation;
        this.state = state;
        this.events = events;
    }

    /** @return the session token, or empty when the code is unknown, expired, replayed or the session is over */
    @Transactional
    public Optional<Exchange> exchange(String code) {
        Instant now = Instant.now();
        String hash = ImpersonationSession.hashHandoffCode(code);
        Optional<ImpersonationSession> found = sessions.findByHandoffCodeHash(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ImpersonationSession session = found.get();
        if (sessions.consumeHandoff(hash, now) != 1) {
            // Replayed, expired or already ended: a replayed code means the URL leaked, so the
            // session it belongs to is ended rather than left usable by the first holder.
            if (session.getHandoffConsumedAt() != null && session.isActive(now)) {
                end(session.getId(), null, "HANDOFF_REPLAY");
            }
            return Optional.empty();
        }
        ImpersonationSession live = sessions.findById(session.getId()).orElseThrow();
        Optional<AppUser> actor = users.findById(live.getActorId()).filter(AppUser::isEnabled);
        if (actor.isEmpty()) {
            return Optional.empty();
        }
        long ttl = Math.max(1, live.getExpiresAt().getEpochSecond() - now.getEpochSecond());
        events.publishEvent(new ImpersonationHandoffExchangedEvent(
                live.getId(), live.getActorId(), live.getTargetEntityId(), live.getMode().name()));
        return Optional.of(new Exchange(minter.mintImpersonationToken(actor.get(), live, ttl), live, actor.get(), ttl));
    }

    /** Ends a session (idempotent), revokes its jti and records an audit event. */
    @Transactional
    public void end(UUID sessionId, UUID endedBy, String reason) {
        sessions.findById(sessionId).ifPresent(s -> {
            if (s.getEndedAt() != null) {
                return;
            }
            Instant now = Instant.now();
            s.end(endedBy, reason, now);
            sessions.save(s);
            revocation.revokeSession(s.getId().toString(), s.getActorId(), s.getExpiresAt(), "IMPERSONATION_" + reason);
            state.evictImpersonation(s.getId());
            events.publishEvent(new ImpersonationEndedEvent(s.getId(), s.getActorId(), s.getTargetEntityId(), reason));
        });
    }

    /** Ends every open session past its expiry; used by the housekeeping job. */
    @Transactional
    public int endExpired() {
        var expired = sessions.findByEndedAtIsNullAndExpiresAtBefore(Instant.now());
        expired.forEach(s -> end(s.getId(), null, "EXPIRED"));
        return expired.size();
    }
}
