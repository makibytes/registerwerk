package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A (account, source address) pair was locked out of the built-in login (K3, 6-10). Published once
 * per lock episode, never per blocked request, so an attack cannot flood the audit log. Carries only
 * the attacker-controlled e-mail string (capped at 254 characters) and the source address — it does
 * not say whether the account exists.
 */
public record LoginLockedEvent(String email, String clientIp, int episode, Instant lockedUntil)
        implements AuditableEvent {

    @Override public String eventType()   { return "LOGIN_LOCKED"; }
    @Override public String subjectType() { return "LoginThrottle"; }
    @Override public UUID   subjectId()   { return UUID.nameUUIDFromBytes(("login:" + email).getBytes(StandardCharsets.UTF_8)); }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "ANONYMOUS"; }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("email", email);
        p.put("clientIp", clientIp);
        p.put("episode", episode);
        p.put("lockedUntil", lockedUntil.toString());
        return p;
    }
}
