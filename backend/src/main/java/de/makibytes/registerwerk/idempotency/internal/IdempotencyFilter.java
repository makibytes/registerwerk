package de.makibytes.registerwerk.idempotency.internal;

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
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in {@code Idempotency-Key} request-replay protection (F-BLOCKER-2): entirely a no-op
 * unless the caller sends the header, so it cannot change behavior for any existing client.
 * Scoped per authenticated entity — an unauthenticated request (no {@code entity_id} claim) is
 * passed through untouched, since there is no safe tenant boundary to key the record by.
 * <p>
 * Any unexpected failure inside the idempotency bookkeeping itself fails OPEN (logs and lets the
 * request through unprotected) rather than blocking or corrupting a real request — this is a
 * reliability nicety layered on top of the API, not a control anything else depends on for
 * correctness.
 */
class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String HEADER = "Idempotency-Key";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyService service;

    IdempotencyFilter(IdempotencyService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || !MUTATING_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // SecurityUtils.extractEntityId does not itself null-check its argument.
        UUID entityId = authentication != null ? SecurityUtils.extractEntityId(authentication) : null;
        if (entityId == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            handleIdempotent(request, response, chain, entityId, key.trim());
        } catch (Exception e) {
            log.error("Idempotency handling failed unexpectedly — proceeding without protection.", e);
            if (!response.isCommitted()) {
                chain.doFilter(request, response);
            }
        }
    }

    private void handleIdempotent(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                   UUID entityId, String key) throws ServletException, IOException {
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String requestHash = hash(request.getMethod(), request.getRequestURI(), cachedRequest.getCachedBody());

        IdempotencyService.Outcome outcome = service.checkOrStart(entityId, key, requestHash);

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
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            String body = new String(cachedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            service.complete(recordId, cachedResponse.getStatus(), body);
            cachedResponse.copyBodyToResponse();
        }
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
