package de.makibytes.registerwerk.shared;

/**
 * A chain-side capacity or availability condition that is expected to clear on its own (e.g. all
 * immediate-submission slots busy, an RPC node briefly rejecting a call). Nothing was signed or
 * broadcast when this is thrown, so the caller may retry unchanged; the web layer maps it to
 * HTTP 503 with a {@code Retry-After} hint.
 */
public class TransientChainException extends RuntimeException {

    private final int retryAfterSeconds;

    public TransientChainException(String message) {
        this(message, 10);
    }

    public TransientChainException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
