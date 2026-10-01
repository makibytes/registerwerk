package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditEventCapture: event time and impersonation stamp at publish (6-12, 6-31)")
class AuditEventCaptureTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
    private final AuditEventCapture capture = new AuditEventCapture(publisher, new SimpleMeterRegistry());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private record Ev(String role) implements AuditableEvent {
        public String eventType() { return "EV"; }
        public String subjectType() { return "S"; }
        public UUID subjectId() { return UUID.randomUUID(); }
        public UUID actorId() { return UUID.randomUUID(); }
        public String actorRole() { return role; }
        public Map<String, Object> payload() { return Map.of("k", "v"); }
    }

    @Test
    @DisplayName("occurredAt is the publish time, taken synchronously")
    void eventTimeIsPublishTime() {
        Instant before = Instant.now().minusMillis(1);
        capture.on(new Ev("TRADER"));
        Instant after = Instant.now().plusMillis(1);
        AuditRecord r = (AuditRecord) published.get(0);
        assertThat(r.occurredAt()).isBetween(before, after);
        assertThat(r.actorRole()).isEqualTo("TRADER");
    }

    @Test
    @DisplayName("an impersonated action is stamped REGISTRY_ADMIN_IMPERSONATING with _imp in the hashed payload")
    void impersonationStamp() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("admin-1")
                .claim("imp", true).claim("imp_session", "sess-1").claim("imp_mode", "READ_ONLY")
                .claim("entity_id", "ent-9").build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null));
        capture.on(new Ev("TRADER"));
        AuditRecord r = (AuditRecord) published.get(0);
        assertThat(r.actorRole()).isEqualTo("REGISTRY_ADMIN_IMPERSONATING");
        assertThat(r.payload()).containsEntry("k", "v");
        @SuppressWarnings("unchecked")
        Map<String, Object> imp = (Map<String, Object>) r.payload().get("_imp");
        assertThat(imp).containsEntry("sessionId", "sess-1").containsEntry("impersonatorId", "admin-1")
                .containsEntry("onBehalfOfEntityId", "ent-9").containsEntry("mode", "READ_ONLY");
    }

    @Test
    @DisplayName("an oversized actor role is clamped at capture instead of poisoning the queue")
    void oversizedRoleClamped() {
        capture.on(new Ev("R".repeat(200)));
        assertThat(((AuditRecord) published.get(0)).actorRole()).hasSize(64);
    }

    @Test
    @DisplayName("a captured event instance is recognised (legacy listener skips it)")
    void capturedInstanceTracked() {
        Ev ev = new Ev("A");
        capture.on(ev);
        assertThat(capture.wasCaptured(ev)).isTrue();
        assertThat(capture.wasCaptured(new Ev("A"))).isFalse();
    }
}
