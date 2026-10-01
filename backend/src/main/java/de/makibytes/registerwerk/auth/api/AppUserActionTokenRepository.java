package de.makibytes.registerwerk.auth.api;

import de.makibytes.registerwerk.auth.api.AppUserActionToken;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserActionTokenRepository extends JpaRepository<AppUserActionToken, UUID> {

    Optional<AppUserActionToken> findByTokenHash(String tokenHash);

    List<AppUserActionToken> findByAppUserIdAndTokenTypeAndConsumedAtIsNull(UUID appUserId, AppUserActionTokenType tokenType);

    /**
     * Burns every unconsumed registration / password-reset token of the account. Called when the
     * account is disabled, deleted or its entity terminated, so a withdrawn invite or a leaked
     * reset link cannot be redeemed later (6-02).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update AppUserActionToken t set t.consumedAt = CURRENT_TIMESTAMP where t.appUserId = :userId and t.consumedAt is null")
    int invalidateAllForUser(@Param("userId") UUID userId);
}
