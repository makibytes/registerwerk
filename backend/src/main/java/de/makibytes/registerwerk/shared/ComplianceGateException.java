package de.makibytes.registerwerk.shared;

/**
 * Thrown when a compliance gate (KYC status, sanctions screening, §16 eWpG Sperrvermerk,
 * Travel Rule / MiCA counterparty checks) blocks an action. A subtype of
 * {@link IllegalStateException} — not a new top-level exception type — so it keeps mapping to
 * 409 via {@code GlobalExceptionHandler.handleIllegalState} without any behavior change for
 * existing callers; the only difference is that this subtype is also recorded as a rejected
 * action in the tamper-evident audit log, whereas the many unrelated {@code IllegalStateException}
 * call sites elsewhere in the codebase (config/infra errors, unexpected invariant violations)
 * deliberately are not — blanket-recording every {@code IllegalStateException} would flood the
 * audit log with system errors that have nothing to do with "someone tried a forbidden action."
 */
public class ComplianceGateException extends IllegalStateException {

    public ComplianceGateException(String message) {
        super(message);
    }

    public ComplianceGateException(String message, Throwable cause) {
        super(message, cause);
    }
}
