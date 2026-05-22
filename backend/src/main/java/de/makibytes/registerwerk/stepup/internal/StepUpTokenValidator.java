package de.makibytes.registerwerk.stepup.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Validates the second-approver token for the 4-eyes (Vieraugenprinzip) check.
 * The token must:
 * - Be a valid JWT (decoded by the same JwtDecoder as the primary token)
 * - Carry acr=stepup and be fresh (≤10 min)
 * - Carry role REGISTRY_ADMIN
 * - Have a different sub than the primary caller (cannot self-approve)
 */
@Component
class StepUpTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(StepUpTokenValidator.class);

    private final JwtDecoder jwtDecoder;

    StepUpTokenValidator(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    void validateDualControlToken(String rawToken, String primarySub, String action) {
        Jwt approverJwt;
        try {
            approverJwt = jwtDecoder.decode(rawToken);
        } catch (JwtException e) {
            throw new AccessDeniedException("Invalid dual-control token: " + e.getMessage());
        }

        // Must be different person
        if (primarySub.equals(approverJwt.getSubject())) {
            log.warn("Dual-control self-approval attempt: sub={} action={}", primarySub, action);
            throw new AccessDeniedException("Dual control: approver must be a different user from the initiator.");
        }

        // Must be REGISTRY_ADMIN
        List<String> roles = approverJwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("REGISTRY_ADMIN")) {
            throw new AccessDeniedException("Dual-control approver must have REGISTRY_ADMIN role.");
        }

        // Must be fresh step-up token
        String acr = approverJwt.getClaimAsString("acr");
        if (!"stepup".equals(acr)) {
            throw new AccessDeniedException("Dual-control approver token must have acr=stepup.");
        }
        Instant iat = approverJwt.getIssuedAt();
        if (iat == null || iat.isBefore(Instant.now().minusSeconds(600))) {
            throw new AccessDeniedException("Dual-control approver step-up token expired (max 10 min).");
        }

        log.info("Dual-control approved: initiator={} approver={} action={}", primarySub, approverJwt.getSubject(), action);
    }
}
