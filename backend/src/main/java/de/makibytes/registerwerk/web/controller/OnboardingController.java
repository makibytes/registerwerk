package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.customer.OnboardingService;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.entity.OnboardingToken;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import de.makibytes.registerwerk.web.dto.OnboardingTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for entity onboarding token creation and consumption.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private static final Logger log = LoggerFactory.getLogger(OnboardingController.class);

    private final OnboardingService onboardingService;
    private final LegalEntityRepository legalEntityRepository;

    public OnboardingController(
            OnboardingService onboardingService,
            LegalEntityRepository legalEntityRepository) {
        this.onboardingService = onboardingService;
        this.legalEntityRepository = legalEntityRepository;
    }

    /**
     * Creates a one-time onboarding token for the given entity.
     * The cleartext token is returned in the response body (send via email).
     */
    @PostMapping("/tokens")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<OnboardingTokenResponse> createOnboardingToken(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        UUID entityId = UUID.fromString(body.get("entityId"));
        UUID issuedBy = extractActorId(auth);
        String cleartext = onboardingService.generateToken(entityId, issuedBy);

        // Fetch the stored token to get the expiry
        OnboardingToken token = onboardingService.validateToken(cleartext).orElse(null);
        OnboardingTokenResponse response = new OnboardingTokenResponse(
            cleartext,
            token != null ? token.getExpiresAt() : null,
            entityId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Public endpoint: returns entity summary and token expiry for informational display.
     * No authentication required — the token itself is the credential.
     */
    @GetMapping("/token-info/{token}")
    public ResponseEntity<Map<String, Object>> getTokenInfo(@PathVariable String token) {
        Optional<OnboardingToken> opt = onboardingService.validateToken(token);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Token not found or expired"));
        }
        OnboardingToken onboardingToken = opt.get();
        Optional<LegalEntity> entityOpt = legalEntityRepository.findById(onboardingToken.getLegalEntityId());

        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("entityId", onboardingToken.getLegalEntityId());
        info.put("entityName", entityOpt.map(LegalEntity::getCurrentName).orElse("Unknown"));
        info.put("entityRegistrationNumber", entityOpt.map(LegalEntity::getRegistrationNumber).orElse(""));
        info.put("registrationCountry", entityOpt.map(LegalEntity::getRegistrationCountry).orElse(""));
        info.put("expiresAt", onboardingToken.getExpiresAt());
        info.put("alreadyUsed", false);
        return ResponseEntity.ok(info);
    }

    /**
     * Public endpoint: completes the onboarding process and activates the entity.
     * No authentication required — the token serves as the proof of identity.
     */
    @PostMapping("/complete")
    public ResponseEntity<Map<String, String>> completeOnboarding(
            @RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token is required"));
        }
        onboardingService.completeOnboarding(token, null);
        return ResponseEntity.ok(Map.of("message", "Onboarding completed successfully"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID extractActorId(Authentication auth) {
        if (auth == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
