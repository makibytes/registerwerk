package de.makibytes.registerwerk.notification.api;

/** An e-mail could not be delivered; thrown by {@link EmailPort#sendHtmlOrThrow} so event listeners are retried. */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
