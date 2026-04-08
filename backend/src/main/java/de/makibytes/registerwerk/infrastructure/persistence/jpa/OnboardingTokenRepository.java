package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.entity.OnboardingToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OnboardingTokenRepository extends JpaRepository<OnboardingToken, UUID> {

    Optional<OnboardingToken> findByTokenHash(String hash);

    Optional<OnboardingToken> findByLegalEntityIdAndUsedAtIsNull(UUID entityId);
}
