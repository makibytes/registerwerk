package de.makibytes.registerwerk.idempotency.internal;

import de.makibytes.registerwerk.idempotency.api.IdempotencyContext;
import de.makibytes.registerwerk.idempotency.api.IdempotencyRecord;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key} request-replay protection (F-BLOCKER-2, P4B-7).
 * <ul>
 *   <li>Opt-in by default: without the header a request passes through untouched, so it cannot
 *       change behavior for any existing client.</li>
 *   <li>Mandatory on handlers annotated {@code @RequiresIdempotencyKey} (decided by
 *       {@code keyRequired}): a missing or malformed key is answered 400 before anything runs, and a
 *       failure of the bookkeeping itself answers 503 instead of proceeding unprotected.</li>
 *   <li>Scoped per actor: the {@code entity_id} claim (customer tenant) or, for operator tokens that
 *       carry none, the JWT subject (user) with a distinct scope, so operator calls are protected.</li>
 * </ul>
 * For non-mandatory endpoints an unexpected failure inside the bookkeeping still fails OPEN (logged);
 * a failure of the downstream chain itself is never retried by this filter.
 */
class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    static final String HEADER = "Idempotency-Key";
    static final String CODE_REQUIRED = "IDEMPOTENCY_KEY_REQUIRED";
    static final String CODE_INVALID = "IDEMPOTENCY_KEY_INVALID";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** UUIDs, ULIDs, prefixed tokens; long enough that a hand-typed "1" cannot collide across actions. */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:\\-]{8,255}");

    private final IdempotencyService service;
    private final Predicate<HttpServletRequest> keyRequired;
    private final Predicate<HttpServletRequest> noReplay;

    IdempotencyFilter(IdempotencyService service) {
        this(service, request -> false);
    }

    IdempotencyFilter(IdempotencyService service, Predicate<HttpServletRequest> keyRequired) {
        this(service, keyRequired, request -> false);
    }

    IdempotencyFilter(IdempotencyService service, Predicate<HttpServletRequest> keyRequired,
                      Predicate<HttpServletRequest> noReplay) {
        this.service = service;
        this.keyRequired = keyRequired;
        this.noReplay = noReplay;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HEADER);
        boolean missing = header == null || header.isBlank();
        boolean required = keyRequired.test(request);
        if (missing && !required) {
            chain.doFilter(request, response);
            return;
        }
        if (required) {
            if (missing) {
                reject(request, response, 400, CODE_REQUIRED,
                        "Header 'Idempotency-Key' is required on this endpoint (a unique value per user action, "
                                + "reused when the same request is retried)");
                return;
            }
            if (!VALID_KEY.matcher(header.trim()).matches()) {
                reject(request, response, 400, CODE_INVALID,
                        "Header 'Idempotency-Key' must be 8-255 characters of [A-Za-z0-9._:-] (a UUID is ideal)");
                return;
            }
        }
        String key = header.trim();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Scope scope = resolveScope(authentication);
        if (scope == null) {
            // Unauthenticated: Spring Security has already rejected anything protected; nothing to scope by.
            chain.doFilter(request, response);
            return;
        }

        boolean multipart = request.getContentType() != null
                && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
        HttpServletRequest downstream = request;
        IdempotencyService.Outcome outcome;
        try {
            byte[] body = new byte[0];
            if (!multipart) {
                CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
                downstream = cached;
                body = cached.getCachedBody();
            }
            // A multipart upload is not buffered (the parts are parsed downstream): its request hash
            // covers method + path + query + length, the key does the rest. The content type is left out
            // because it carries the random multipart boundary, which changes on every browser retry.
            String target = request.getRequestURI() + "?" + canonicalQuery(request.getQueryString());
            String requestHash = multipart
                    ? hash(request.getMethod(), target, ("multipart|" + request.getContentLengthLong()).getBytes(StandardCharsets.UTF_8))
                    : hash(request.getMethod(), target, body);
            outcome = service.checkOrStart(scope.kind(), scope.id(), key, requestHash);
        } catch (Exception e) {
            if (required) {
                log.error("Idempotency bookkeeping failed on a mandatory-key endpoint - refusing the request.", e);
                if (!response.isCommitted()) {
                    reject(request, response, 503, "IDEMPOTENCY_UNAVAILABLE",
                            "Idempotency service unavailable - retry with the same Idempotency-Key");
                }
                return;
            }
            log.error("Idempotency handling failed unexpectedly - proceeding without protection.", e);
            chain.doFilter(request, response);
            return;
        }

        if (outcome instanceof IdempotencyService.Outcome.Replay replay) {
            response.setStatus(replay.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("X-Idempotent-Replay", "true");
            if (replay.body() != null) {
                response.getWriter().write(replay.body());
            }
            return;
        }
        if (outcome instanceof IdempotencyService.Outcome.Conflict conflict) {
            response.setStatus(conflict.httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"" + conflict.message().replace("\"", "'") + "\"}");
            return;
        }

        UUID recordId = ((IdempotencyService.Outcome.Proceed) outcome).recordId();
        boolean secretResponse = noReplay.test(request);
        IdempotencyContext.bind(request, new IdempotencyContext.Key(scope.kind(), scope.id().toString(), key));
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        boolean threw = true;
        try {
            chain.doFilter(downstream, cachedResponse);
            threw = false;
        } finally {
            // An exception escaping the chain must not be recorded as a successful 200: report it as a
            // server failure so the record is released and the retry re-runs (the outbox key still
            // prevents a second chain submission).
            int status = threw ? 500 : cachedResponse.getStatus();
            try {
                if (secretResponse) {
                    service.release(recordId); // 7A-07: one-time secrets are never stored or replayed
                } else {
                    service.complete(recordId, status, new String(cachedResponse.getContentAsByteArray(), StandardCharsets.UTF_8));
                }
            } catch (RuntimeException e) {
                log.error("Could not complete idempotency record {} - it stays IN_PROGRESS until cleanup.", recordId, e);
            }
            if (!threw) {
                cachedResponse.copyBodyToResponse();
            }
        }
    }

    /** Query string with its parameters sorted, so parameter order does not change the hash but values do. */
    static String canonicalQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String[] pairs = query.split("&");
        java.util.Arrays.sort(pairs);
        return String.join("&", pairs);
    }

    private record Scope(String kind, UUID id) {}

    /** Customer tokens: the entity; operator tokens (no entity_id claim): the acting user, from the JWT sub. */
    static Scope resolveScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        UUID entityId = SecurityUtils.extractEntityId(authentication);
        if (entityId != null) {
            return new Scope(IdempotencyRecord.SCOPE_ENTITY, entityId);
        }
        UUID userId = SecurityUtils.extractUserId(authentication);
        if (userId != null) {
            return new Scope(IdempotencyRecord.SCOPE_USER, userId);
        }
        // Non-UUID subject (external OIDC issuer): derive a stable id from the subject string.
        String subject = authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : authentication.getName();
        if (subject == null || subject.isBlank() || "anonymousUser".equals(subject)) {
            return null;
        }
        return new Scope(IdempotencyRecord.SCOPE_USER,
                UUID.nameUUIDFromBytes(("sub:" + subject).getBytes(StandardCharsets.UTF_8)));
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, int status, String code,
                               String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\""
                + message.replace("\"", "'") + "\",\"timestamp\":\"" + Instant.now() + "\",\"path\":\""
                + request.getRequestURI().replace("\"", "") + "\"}");
    }

    private static String hash(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
