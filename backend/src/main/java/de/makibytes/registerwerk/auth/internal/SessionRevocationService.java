package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.SessionRevocationPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SessionRevocationService implements SessionRevocationPort {

    private final AppUserRepository users;
    private final SessionRevocationRepository revocations;
    private final SessionStateService state;

    SessionRevocationService(AppUserRepository users, SessionRevocationRepository revocations,
                             SessionStateService state) {
        this.users = users;
        this.revocations = revocations;
        this.state = state;
    }

    @Override
    @Transactional
    public void revokeAll(UUID userId) {
        users.findById(userId).ifPresent(u -> {
            u.revokeSessions();
            users.save(u);
        });
    }

    @Override
    @Transactional
    public void revokeSession(String jti, UUID userId, Instant tokenExpiresAt, String reason) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (!revocations.existsById(jti)) {
            revocations.save(new SessionRevocation(jti, userId, tokenExpiresAt, reason));
        }
        state.markRevoked(jti);
    }
}
