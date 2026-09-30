package de.makibytes.registerwerk.idempotency.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller (all its mutating handlers) or a single handler method as money-/state-moving:
 * a POST/PUT/PATCH/DELETE without a valid {@code Idempotency-Key} header is rejected with
 * {@code 400 IDEMPOTENCY_KEY_REQUIRED} before the handler runs, so a retry after a timeout can never
 * execute the action twice. Not a global requirement: unannotated endpoints stay opt-in.
 * <p>
 * The key is scoped per actor (entity for customer tokens, user for operator tokens) and the request
 * hash covers method + path + body, i.e. per endpoint. Service code reads it through
 * {@link IdempotencyContext}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresIdempotencyKey {
}
