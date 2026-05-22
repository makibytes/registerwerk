package de.makibytes.registerwerk.auth.api;

import de.makibytes.registerwerk.auth.api.AppUserActionToken;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserActionTokenRepository extends JpaRepository<AppUserActionToken, UUID> {

    Optional<AppUserActionToken> findByTokenHash(String tokenHash);

    List<AppUserActionToken> findByAppUserIdAndTokenTypeAndConsumedAtIsNull(UUID appUserId, AppUserActionTokenType tokenType);
}
