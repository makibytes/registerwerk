package de.makibytes.registerwerk.wallet.internal;

/**
 * Failure of a cloud-KMS call. {@link Kind} decides whether the bounded retry loop in
 * {@link KmsSignerService} may try again: signing is side-effect free, so TIMEOUT/TRANSIENT are safe
 * to repeat, PERMANENT (permission denied, key missing, wrong algorithm, malformed response) never is.
 * Messages must never carry digests or key material.
 */
public class KmsSigningException extends RuntimeException {

    public enum Kind { TIMEOUT, TRANSIENT, PERMANENT }

    private final Kind kind;

    public KmsSigningException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public KmsSigningException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind != Kind.PERMANENT;
    }
}
