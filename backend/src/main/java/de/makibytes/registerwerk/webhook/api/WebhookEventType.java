package de.makibytes.registerwerk.webhook.api;

/**
 * The curated set of events available to external subscribers — deliberately a narrow allow-list
 * rather than every internal {@code AuditableEvent} (which includes operator/admin actions no
 * external integrator should see). Names match the corresponding {@code AuditableEvent.eventType()}
 * string so payloads and this enum stay traceable to their source event.
 */
public enum WebhookEventType {
    KYC_APPROVED,
    KYC_REJECTED,
    ASSET_APPROVED,
    ASSET_REJECTED,
    SUBSCRIPTION_ORDER_ALLOCATED,
    SUBSCRIPTION_ORDER_CONFIRMED,
    SUBSCRIPTION_ORDER_REJECTED,
    TRADE_EXECUTED,
    TRADE_PAYMENT_CONFIRMED,
    TRADE_PAYMENT_DISPUTED
}
