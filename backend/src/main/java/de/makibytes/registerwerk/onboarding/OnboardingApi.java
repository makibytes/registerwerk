package de.makibytes.registerwerk.onboarding;

import de.makibytes.registerwerk.onboarding.api.OnboardingToken;

import java.util.Optional;
import java.util.UUID;

/** Public API for customer onboarding token queries. */
public interface OnboardingApi {

    Optional<OnboardingToken> findToken(UUID id);

    Optional<OnboardingToken> findByToken(String token);
}
