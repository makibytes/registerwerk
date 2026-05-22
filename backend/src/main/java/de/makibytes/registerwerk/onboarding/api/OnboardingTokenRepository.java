package de.makibytes.registerwerk.onboarding.api;

import de.makibytes.registerwerk.onboarding.api.OnboardingToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OnboardingTokenRepository extends JpaRepository<OnboardingToken, UUID> {

    Optional<OnboardingToken> findByTokenHash(String hash);

    Optional<OnboardingToken> findByLegalEntityIdAndUsedAtIsNull(UUID entityId);
}
