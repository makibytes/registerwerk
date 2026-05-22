package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.onboarding.internal.OnboardingService;
import de.makibytes.registerwerk.auth.AuthApi;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.onboarding.api.OnboardingToken;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.onboarding.api.OnboardingTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingService unit tests")
class OnboardingServiceTest {

    @Mock
    private OnboardingTokenRepository onboardingTokenRepository;

    @Mock
    private LegalEntityRepository legalEntityRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AuthApi authApi;

    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        RegisterwerkAuthProperties authProperties = new RegisterwerkAuthProperties();
        authProperties.setEntraEnabled(false);
        onboardingService = new OnboardingService(
            onboardingTokenRepository,
            legalEntityRepository,
            eventPublisher,
            authApi,
            authProperties,
            48,
            "http://localhost:4201"
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LegalEntity buildEntity() {
        LegalEntity entity = new LegalEntity();
        entity.setId(UUID.randomUUID());
        entity.setCurrentName("Test Corp AG");
        entity.setType(EntityType.ISSUER);
        entity.setStatus(EntityStatus.PENDING_ONBOARDING);
        return entity;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OnboardingToken buildValidToken(UUID entityId) {
        OnboardingToken token = new OnboardingToken();
        token.setId(UUID.randomUUID());
        token.setLegalEntityId(entityId);
        token.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        return token;
    }

    // ── generateToken ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateToken should store the SHA-256 hash, not the cleartext token")
    void generateToken_shouldStoreHashNotCleartext() {
        LegalEntity entity = buildEntity();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(onboardingTokenRepository.findByLegalEntityIdAndUsedAtIsNull(entity.getId()))
            .thenReturn(Optional.empty());
        when(onboardingTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String cleartext = onboardingService.generateToken(entity.getId(), UUID.randomUUID());

        ArgumentCaptor<OnboardingToken> captor = ArgumentCaptor.forClass(OnboardingToken.class);
        verify(onboardingTokenRepository).save(captor.capture());

        OnboardingToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isNotEqualTo(cleartext);
        assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(cleartext));
    }

    @Test
    @DisplayName("generateToken should set expiry about 48 hours in the future")
    void generateToken_shouldSetExpiryInFuture() {
        LegalEntity entity = buildEntity();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(onboardingTokenRepository.findByLegalEntityIdAndUsedAtIsNull(entity.getId()))
            .thenReturn(Optional.empty());
        when(onboardingTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        onboardingService.generateToken(entity.getId(), UUID.randomUUID());

        ArgumentCaptor<OnboardingToken> captor = ArgumentCaptor.forClass(OnboardingToken.class);
        verify(onboardingTokenRepository).save(captor.capture());

        Instant expiresAt = captor.getValue().getExpiresAt();
        Instant now = Instant.now();
        assertThat(expiresAt)
            .isAfter(now.plus(47, ChronoUnit.HOURS))
            .isBefore(now.plus(49, ChronoUnit.HOURS));
    }

    // ── validateToken ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("validateToken should return empty Optional for an expired token")
    void validateToken_shouldReturnEmptyForExpiredToken() {
        String cleartext = "sometoken";
        String hash = sha256Hex(cleartext);

        OnboardingToken expiredToken = new OnboardingToken();
        expiredToken.setId(UUID.randomUUID());
        expiredToken.setLegalEntityId(UUID.randomUUID());
        expiredToken.setTokenHash(hash);
        expiredToken.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)); // expired

        when(onboardingTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expiredToken));

        Optional<OnboardingToken> result = onboardingService.validateToken(cleartext);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("validateToken should return empty Optional for an already-used token")
    void validateToken_shouldReturnEmptyForUsedToken() {
        String cleartext = "anothertoken";
        String hash = sha256Hex(cleartext);

        OnboardingToken usedToken = new OnboardingToken();
        usedToken.setId(UUID.randomUUID());
        usedToken.setLegalEntityId(UUID.randomUUID());
        usedToken.setTokenHash(hash);
        usedToken.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        usedToken.setUsedAt(Instant.now().minus(1, ChronoUnit.HOURS)); // already used

        when(onboardingTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(usedToken));

        Optional<OnboardingToken> result = onboardingService.validateToken(cleartext);

        assertThat(result).isEmpty();
    }

    // ── completeOnboarding ────────────────────────────────────────────────────

    @Test
    @DisplayName("completeOnboarding should mark the token as used by setting usedAt")
    void completeOnboarding_shouldMarkTokenUsed() {
        UUID entityId = UUID.randomUUID();
        String cleartext = "validtoken";
        String hash = sha256Hex(cleartext);

        OnboardingToken token = buildValidToken(entityId);
        token.setTokenHash(hash);

        LegalEntity entity = buildEntity();
        entity.setId(entityId);

        when(onboardingTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(token));
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(onboardingTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        onboardingService.completeOnboarding(cleartext, "admin@test.local", "Jane Admin", "Sup3rSecret!", UUID.randomUUID());

        assertThat(token.getUsedAt()).isNotNull();
        assertThat(token.isUsed()).isTrue();
    }

    @Test
    @DisplayName("completeOnboarding should set entity status to ACTIVE")
    void completeOnboarding_shouldActivateEntity() {
        UUID entityId = UUID.randomUUID();
        String cleartext = "activatetoken";
        String hash = sha256Hex(cleartext);

        OnboardingToken token = buildValidToken(entityId);
        token.setTokenHash(hash);

        LegalEntity entity = buildEntity();
        entity.setId(entityId);
        entity.setStatus(EntityStatus.PENDING_ONBOARDING);

        when(onboardingTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(token));
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(onboardingTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        onboardingService.completeOnboarding(cleartext, "admin@test.local", "Jane Admin", "Sup3rSecret!", UUID.randomUUID());

        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        verify(legalEntityRepository).save(entity);
    }
}
