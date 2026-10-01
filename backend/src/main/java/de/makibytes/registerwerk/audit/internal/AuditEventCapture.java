package de.makibytes.registerwerk.audit.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.events.RejectedActionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous capture of every {@link AuditableEvent} (and {@link RejectedActionEvent}) in the
 * publisher's thread/transaction (6-12): fixes the event time at publish, stamps the impersonation
 * context (6-31) and republishes one {@link AuditRecord} that the async recorder persists.
 *
 * <p>Impersonation contract with the session model: a JWT with claim {@code imp=true} (optional
 * {@code imp_mode}); the session id is the token id ({@code jti}); {@code sub} is the real admin, {@code entity_id} the
 * entity acted on. Such actions are recorded with {@code actor_role=REGISTRY_ADMIN_IMPERSONATING}
 * and a hashed payload key {@code _imp = {sessionId, impersonatorId, onBehalfOfEntityId, mode}}.
 */
@Component
class AuditEventCapture {

    static final String IMPERSONATING_ROLE = "REGISTRY_ADMIN_IMPERSONATING";
    private static final Logger log = LoggerFactory.getLogger(AuditEventCapture.class);

    private final ApplicationEventPublisher publisher;
    private final Counter clamped;
    /** Identity-keyed (weak) set of events already captured, so the legacy listener skips them. */
    private final Cache<Object, Boolean> captured = Caffeine.newBuilder().weakKeys().build();

    AuditEventCapture(ApplicationEventPublisher publisher, MeterRegistry meterRegistry) {
        this.publisher = publisher;
        this.clamped = Counter.builder("registerwerk_audit_capture_clamped_total")
                .description("Audit fields truncated at capture because they exceed their column length")
                .register(meterRegistry);
    }

    boolean wasCaptured(Object event) {
        return captured.getIfPresent(event) != null;
    }

    @EventListener
    void on(AuditableEvent event) {
        AuditEvent base = AuditEvent.from(event);
        publish(event, new AuditRecord(base.getEventType(), base.getSubjectType(), base.getSubjectId(),
                base.getActorId(), base.getActorRole(), base.getPayload(), now(),
                base.getReversesEventId(), base.getCorrelationId()));
    }

    @EventListener
    void on(RejectedActionEvent event) {
        publish(event, new AuditRecord(event.eventType(), "HttpRequest",
                UUID.nameUUIDFromBytes((event.httpMethod() + " " + event.path()).getBytes(StandardCharsets.UTF_8)),
                event.actorId(), event.actorRole(),
                Map.of("path", event.path(), "method", event.httpMethod(),
                        "reason", event.reason() != null ? event.reason() : ""),
                now(), null, null));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private void publish(Object source, AuditRecord raw) {
        captured.put(source, Boolean.TRUE);
        AuditRecord stamped = stamp(raw);
        publisher.publishEvent(stamped.recordId() != null ? stamped
                : new AuditRecord(stamped.eventType(), stamped.subjectType(), stamped.subjectId(), stamped.actorId(),
                        stamped.actorRole(), stamped.payload(), stamped.occurredAt(), stamped.reversesEventId(),
                        stamped.correlationId(), UUID.randomUUID()));
    }

    AuditRecord stamp(AuditRecord r) {
        String role = r.actorRole();
        Map<String, Object> payload = r.payload();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            if (SecurityUtils.isImpersonatingAdmin(auth)) {
                role = IMPERSONATING_ROLE;
                Map<String, Object> imp = new LinkedHashMap<>();
                // K1 issues the impersonation session id as the token id (jti)
                imp.put("sessionId", jwt.getId() != null ? jwt.getId() : jwt.getClaimAsString("imp_session"));
                imp.put("impersonatorId", jwt.getSubject());
                imp.put("onBehalfOfEntityId", jwt.getClaimAsString("entity_id"));
                imp.put("mode", jwt.getClaimAsString("imp_mode"));
                payload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
                payload.put("_imp", imp);
            } else if (role == null) {
                role = SecurityUtils.primaryRole(auth, null);
            }
        }
        String eventType = clamp(r.eventType(), 100, "eventType");
        String subjectType = clamp(r.subjectType(), 50, "subjectType");
        role = clamp(role, 64, "actorRole");
        return new AuditRecord(eventType, subjectType, r.subjectId(), r.actorId(), role, payload,
                r.occurredAt(), r.reversesEventId(), r.correlationId(), r.recordId());
    }

    private String clamp(String value, int max, String field) {
        if (value != null && value.length() > max) {
            clamped.increment();
            log.error("Audit {} exceeds {} characters and was truncated at capture: {}", field, max, value);
            return value.substring(0, max);
        }
        return value;
    }
}
