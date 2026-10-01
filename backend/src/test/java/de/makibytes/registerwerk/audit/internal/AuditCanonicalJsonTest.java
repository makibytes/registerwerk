package de.makibytes.registerwerk.audit.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Audit canonical JSON v1/v2 (6-11)")
class AuditCanonicalJsonTest {

    private final AuditCanonicalJson json = new AuditCanonicalJson(new ObjectMapper());

    private AuditEvent event(int version) {
        AuditEvent e = new AuditEvent();
        e.setEventType("X");
        e.setSubjectType("T");
        e.setSubjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        e.setActorId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        e.setActorRole("REGISTRY_ADMIN");
        e.setOccurredAt(Instant.parse("2026-01-01T00:00:00.123456Z"));
        e.setPayload(Map.of("b", 1, "a", 2));
        e.setCanonVersion((short) version);
        return e;
    }

    @Test
    @DisplayName("v1 ignores actor/role/time (legacy rows keep verifying); v2 covers them")
    void v2CoversActorRoleTime() {
        String v1 = json.canonicalize(event(1));
        assertThat(v1).isEqualTo(json.canonicalize("X", "T",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), Map.of("a", 2, "b", 1)));
        AuditEvent changedActor = event(2);
        String base = json.canonicalize(event(2));
        changedActor.setActorId(UUID.randomUUID());
        assertThat(json.canonicalize(changedActor)).isNotEqualTo(base);
        AuditEvent changedRole = event(2);
        changedRole.setActorRole("AUDIT");
        assertThat(json.canonicalize(changedRole)).isNotEqualTo(base);
        AuditEvent changedTime = event(2);
        changedTime.setOccurredAt(Instant.parse("2026-01-01T00:00:00.123457Z"));
        assertThat(json.canonicalize(changedTime)).isNotEqualTo(base);
        AuditEvent changedCorr = event(2);
        changedCorr.setCorrelationId(UUID.randomUUID());
        assertThat(json.canonicalize(changedCorr)).isNotEqualTo(base);
        AuditEvent changedRev = event(2);
        changedRev.setReversesEventId(UUID.randomUUID());
        assertThat(json.canonicalize(changedRev)).isNotEqualTo(base);
        assertThat(base).contains("\"occurredAtMicros\":\"1767225600123456\"");
    }

    @Test
    @DisplayName("an unknown canon_version is an error, not a fallback")
    void unknownVersionRejected() {
        assertThatThrownBy(() -> json.canonicalize(event(3))).isInstanceOf(IllegalArgumentException.class);
    }
}
