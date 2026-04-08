package de.makibytes.registerwerk.application.customer;

import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.application.notification.OnboardingEmailService;
import de.makibytes.registerwerk.application.notification.WelcomeEmailService;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.entity.OnboardingToken;
import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OnboardingTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the creation, validation, and completion of onboarding tokens.
 * Tokens are stored as SHA-256 hashes; the cleartext is returned once and sent by email.
 */
@Service
@Transactional
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);
    private static final int TOKEN_BYTES = 36; // 36 random bytes → 48-char Base64URL token
    private static final long TOKEN_TTL_DAYS = 7;

    private final OnboardingTokenRepository onboardingTokenRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final WelcomeEmailService welcomeEmailService;
    private final OnboardingEmailService onboardingEmailService;

    public OnboardingService(
            OnboardingTokenRepository onboardingTokenRepository,
            LegalEntityRepository legalEntityRepository,
            WelcomeEmailService welcomeEmailService,
            OnboardingEmailService onboardingEmailService) {
        this.onboardingTokenRepository = onboardingTokenRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.welcomeEmailService = welcomeEmailService;
        this.onboardingEmailService = onboardingEmailService;
    }

    /**
     * Generates a secure 48-character onboarding token for the given entity.
     * Stores only the SHA-256 hash in the database and returns the cleartext.
     *
     * @param entityId ID of the entity being onboarded
     * @param issuedBy ID of the registry admin creating the token
     * @return cleartext token (send to the entity via a secure channel)
     */
    public String generateToken(UUID entityId, UUID issuedBy) {
        legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        // Invalidate any existing unused token
        onboardingTokenRepository.findByLegalEntityIdAndUsedAtIsNull(entityId)
            .ifPresent(old -> {
                old.setUsedAt(Instant.now()); // mark expired
                onboardingTokenRepository.save(old);
            });

        byte[] randomBytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        String cleartext = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        OnboardingToken token = new OnboardingToken();
        token.setLegalEntityId(entityId);
        token.setTokenHash(sha256Hex(cleartext));
        token.setExpiresAt(Instant.now().plus(TOKEN_TTL_DAYS, ChronoUnit.DAYS));
        token.setIssuedBy(issuedBy);
        onboardingTokenRepository.save(token);

        log.info("Generated onboarding token for entityId={}", entityId);
        return cleartext;
    }

    /**
     * Validates a cleartext token without consuming it.
     *
     * @param cleartextToken the raw token received from the user
     * @return an Optional containing the token if valid (not expired, not used)
     */
    @Transactional(readOnly = true)
    public Optional<OnboardingToken> validateToken(String cleartextToken) {
        String hash = sha256Hex(cleartextToken);
        return onboardingTokenRepository.findByTokenHash(hash)
            .filter(OnboardingToken::isValid);
    }

    /**
     * Completes onboarding: marks the token as used and activates the entity.
     *
     * @param cleartextToken the raw token supplied by the entity representative
     * @param actorId        UUID of the actor completing onboarding (may equal entity contact)
     * @throws IllegalArgumentException if the token is invalid or expired
     */
    public void completeOnboarding(String cleartextToken, UUID actorId) {
        String hash = sha256Hex(cleartextToken);
        OnboardingToken token = onboardingTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new IllegalArgumentException("Invalid onboarding token"));

        if (!token.isValid()) {
            throw new IllegalArgumentException("Onboarding token is expired or already used");
        }

        token.setUsedAt(Instant.now());
        onboardingTokenRepository.save(token);

        LegalEntity entity = legalEntityRepository.findById(token.getLegalEntityId())
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", token.getLegalEntityId()));
        entity.setStatus(EntityStatus.ACTIVE);
        legalEntityRepository.save(entity);

        log.info("Completed onboarding for entityId={}", token.getLegalEntityId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
