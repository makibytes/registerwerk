package de.makibytes.registerwerk.webhook.api;

public enum WebhookDeliveryStatus {
    PENDING,
    SUCCESS,
    /** Terminal after {@code WebhookDispatchService.MAX_ATTEMPTS} exhausted, or a permanent
     *  (4xx) response. Still retried by {@code WebhookRetryJob} until the attempt cap. */
    FAILED
}
