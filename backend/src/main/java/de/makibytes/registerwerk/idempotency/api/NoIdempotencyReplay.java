package de.makibytes.registerwerk.idempotency.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler that returns a one-time secret (Entra Temporary Access Pass, key export, impersonation
 * handoff code, TOTP enrolment secret). The idempotency filter never stores such a response: the record is
 * released when the request completes (like a 5xx) and a retry with the same key re-executes (7A-07).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NoIdempotencyReplay {
}
