package de.makibytes.registerwerk.auth;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserActionToken;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenType;

import java.util.Optional;
import java.util.UUID;

/**
 * Public API for the authentication module.
 * Other modules interact with auth exclusively through this interface.
 */
public interface AuthApi {

    Optional<AppUser> findActiveUser(UUID id);

    Optional<AppUser> findUserByEmail(String email);

    AppUser createInitialCompanyAdmin(UUID legalEntityId, String email, String name, String password, boolean entraEnabled);

    Optional<AppUserActionToken> findValidActionToken(String cleartext, AppUserActionTokenType type);
}
