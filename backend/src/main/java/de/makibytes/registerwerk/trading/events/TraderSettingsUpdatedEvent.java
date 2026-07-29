package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import java.util.Map;
import java.util.UUID;

/** Payload enriched with the new settings values. */
public record TraderSettingsUpdatedEvent(
        UUID entityId, UUID actorId, String actorRole,
        PaymentOption defaultPaymentOption, boolean immediateSettlementEnabled
) implements AuditableEvent {
    public String eventType()   { return "TRADER_SETTINGS_UPDATED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() {
        return Map.of(
                "defaultPaymentOption", defaultPaymentOption != null ? defaultPaymentOption.name() : "",
                "immediateSettlementEnabled", immediateSettlementEnabled
        );
    }
}
