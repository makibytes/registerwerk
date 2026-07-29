package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditEvent.from(...) unit tests")
class AuditEventTest {

    private record PlainEvent(UUID subjectId, Map<String, Object> details) implements AuditableEvent {
        public String eventType() { return "PLAIN"; }
        public String subjectType() { return "Plain"; }
        @Override public UUID actorId() { return UUID.randomUUID(); }
        @Override public String actorRole() { return "REGISTRY_ADMIN"; }
        @Override public Map<String, Object> payload() { return details; }
    }

    private record DualControlEvent(UUID subjectId, UUID dualControlApproverId, Map<String, Object> details)
            implements AuditableEvent {
        public String eventType() { return "DUAL_CONTROL"; }
        public String subjectType() { return "Plain"; }
        @Override public UUID actorId() { return UUID.randomUUID(); }
        @Override public String actorRole() { return "REGISTRY_ADMIN"; }
        @Override public Map<String, Object> payload() { return details; }
    }

    @Test
    @DisplayName("an event with no dual-control approver leaves the payload untouched")
    void from_noApprover_payloadUnchanged() {
        PlainEvent event = new PlainEvent(UUID.randomUUID(), Map.of("k", "v"));

        AuditEvent ae = AuditEvent.from(event);

        assertThat(ae.getPayload()).containsExactlyEntriesOf(Map.of("k", "v"));
    }

    @Test
    @DisplayName("an event with a dual-control approver folds it into the payload under a fixed key")
    void from_withApprover_addsApproverToPayload() {
        UUID approverId = UUID.randomUUID();
        DualControlEvent event = new DualControlEvent(UUID.randomUUID(), approverId, Map.of("reason", "test"));

        AuditEvent ae = AuditEvent.from(event);

        assertThat(ae.getPayload())
                .containsEntry("reason", "test")
                .containsEntry("dualControlApproverId", approverId.toString());
    }

    @Test
    @DisplayName("an event with a dual-control approver and a null details map still gets the approver key")
    void from_withApproverAndNullDetails_addsApproverOnly() {
        UUID approverId = UUID.randomUUID();
        DualControlEvent event = new DualControlEvent(UUID.randomUUID(), approverId, null);

        AuditEvent ae = AuditEvent.from(event);

        assertThat(ae.getPayload()).containsExactlyEntriesOf(Map.of("dualControlApproverId", approverId.toString()));
    }

    @Test
    @DisplayName("default dualControlApproverId() is null when the event doesn't override it")
    void defaultDualControlApproverId_isNull() {
        PlainEvent event = new PlainEvent(UUID.randomUUID(), Map.of());

        assertThat(event.dualControlApproverId()).isNull();
    }
}
