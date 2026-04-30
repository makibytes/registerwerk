package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.entity.AppUserActionToken;
import de.makibytes.registerwerk.domain.enums.AppUserActionTokenType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserActionTokenRepository extends JpaRepository<AppUserActionToken, UUID> {

    Optional<AppUserActionToken> findByTokenHash(String tokenHash);

    List<AppUserActionToken> findByAppUserIdAndTokenTypeAndConsumedAtIsNull(UUID appUserId, AppUserActionTokenType tokenType);
}
