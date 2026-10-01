package de.makibytes.registerwerk.webhook.api;

/** Coarse, customer-visible result of a delivery attempt. Deliberately carries no HTTP status or
 *  exception text: those would let a subscriber use the platform as an internal port/status probe. */
public enum WebhookDeliveryOutcome {
    OK,
    RECEIVER_ERROR,
    UNREACHABLE,
    /** The target URL failed the outbound URL policy (non-public address, bad scheme/port). */
    BLOCKED
}
