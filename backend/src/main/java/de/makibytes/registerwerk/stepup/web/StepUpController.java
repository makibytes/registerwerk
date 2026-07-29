package de.makibytes.registerwerk.stepup.web;

import de.makibytes.registerwerk.stepup.internal.StepUpTokenIssuer;
import de.makibytes.registerwerk.stepup.web.dto.StepUpRequest;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
import de.makibytes.registerwerk.stepup.web.dto.TotpEnrollmentConfirmRequest;
import de.makibytes.registerwerk.stepup.web.dto.TotpEnrollmentResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Issues short-lived step-up tokens after secondary factor verification.
 * Currently supports TOTP (Time-based One-Time Password via RFC 6238).
 * WebAuthn (FIDO2) can be wired as an alternative verifier.
 *
 * POST /api/v1/auth/step-up  { "code": "123456" }
 * → { "stepUpToken": "eyJ..." }
 */
@RestController
@RequestMapping("/api/v1/auth/step-up")
public class StepUpController {

    private static final Logger log = LoggerFactory.getLogger(StepUpController.class);

    private final StepUpTokenIssuer tokenIssuer;

    StepUpController(StepUpTokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StepUpResponse> issueStepUpToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid StepUpRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String stepUpToken = tokenIssuer.issueAfterVerification(userId, request.code(), request.method(), request.action());
        log.info("Step-up token issued for sub={} scope={}", userId, request.action());
        return ResponseEntity.ok(new StepUpResponse(stepUpToken));
    }

    /**
     * Starts TOTP enrolment for the caller — generates a new secret (not yet active) and
     * returns it plus an otpauth:// URI for a QR code. Call {@link #confirmEnrollment} with a
     * code from the resulting authenticator entry to activate it.
     */
    @PostMapping("/enroll")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TotpEnrollmentResponse> enroll(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        StepUpTokenIssuer.EnrollmentStart start = tokenIssuer.enroll(userId);
        log.info("TOTP enrolment started for sub={}", userId);
        return ResponseEntity.ok(new TotpEnrollmentResponse(start.secret(), start.otpauthUri()));
    }

    /** Confirms TOTP enrolment with a code produced by the authenticator app added in {@link #enroll}. */
    @PostMapping("/enroll/confirm")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> confirmEnrollment(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid TotpEnrollmentConfirmRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        tokenIssuer.confirmEnrollment(userId, request.code());
        log.info("TOTP enrolment confirmed for sub={}", userId);
        return ResponseEntity.noContent().build();
    }
}
