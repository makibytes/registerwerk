package de.makibytes.registerwerk.shared.api;

import org.springframework.security.access.AccessDeniedException;

/**
 * An {@link AccessDeniedException} whose message is safe and useful to show to the (already
 * authenticated) caller, plus a stable {@link #code()} the UI can branch on.
 *
 * <p>The global handler answers a plain {@code AccessDeniedException} with a deliberately generic
 * "Access denied". That is right for authorisation failures, but it made a step-up refusal
 * ("you have not enrolled an authenticator", "that code was already used") indistinguishable from a
 * missing permission, so the impersonation picker could only say "Access denied". Throw this where
 * the reason is about the caller's own credential and carries no information about anyone else.
 */
public class CodedAccessDeniedException extends AccessDeniedException {

    private final String code;

    public CodedAccessDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
