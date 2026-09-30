package de.makibytes.registerwerk.idempotency.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code Idempotency-Key} of the current HTTP request (set by the idempotency filter), so a
 * chain-submission layer can persist it on its durable row without threading a parameter through
 * every service signature. Returns null outside a request or when the caller sent no key
 * (scheduled jobs, retries of the outbox itself).
 */
public final class IdempotencyContext {

    static final String ATTRIBUTE = IdempotencyContext.class.getName() + ".KEY";

    private IdempotencyContext() {}

    /** Actor scope + key of the request; {@code counter} numbers the submissions made by it. */
    public record Key(String scope, String scopeId, String key, AtomicInteger counter) {
        public Key(String scope, String scopeId, String key) {
            this(scope, scopeId, key, new AtomicInteger());
        }
    }

    public static void bind(HttpServletRequest request, Key key) {
        request.setAttribute(ATTRIBUTE, key);
    }

    /**
     * Deterministic identity of the NEXT durable submission of this request:
     * {@code <scope>:<scopeId>:<key>#<n>}. The same request replayed with the same key makes the
     * same submissions in the same order, so each maps to the same outbox row. Null when there is
     * no key.
     */
    public static String nextSubmissionKey() {
        HttpServletRequest request = current();
        if (request != null && request.getAttribute(ATTRIBUTE) instanceof Key k) {
            return k.scope() + ":" + k.scopeId() + ":" + k.key() + "#" + k.counter().getAndIncrement();
        }
        return null;
    }

    private static HttpServletRequest current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra
                ? sra.getRequest() : null;
    }
}
