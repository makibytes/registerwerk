package de.makibytes.registerwerk.auth.api;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;

/**
 * Maps an authenticated principal to the {@link AppUser} row that backs it, whichever way they
 * signed in.
 *
 * <p>For a locally minted token this is trivial — {@code sub} <em>is</em> {@code app_user.id}.
 * For an Entra token it is not: {@code sub} and {@code oid} are Entra's identifiers, and the
 * corresponding row carries a DB-generated UUID. Resolving that mapping in one place is what
 * lets {@code EntraPrincipalNormalizationFilter} rewrite the token's {@code sub} so the ~100
 * existing {@code SecurityUtils.extractUserId} call sites keep working untouched.
 */
public interface PrincipalResolver {

    /**
     * The account behind this authentication, provisioning one on first sight of an Entra
     * principal. Empty when the principal is not a JWT, or carries too little to identify or
     * create an account.
     */
    Optional<AppUser> resolve(Authentication authentication);

    /**
     * As {@link #resolve}, but throws rather than returning empty.
     *
     * @throws org.springframework.security.access.AccessDeniedException when no account can be
     *         resolved or provisioned — deliberately fail-closed, since every downstream
     *         authorisation and audit decision depends on this identity
     */
    AppUser requireUser(Authentication authentication);

    /** The Entra object id ({@code oid}) on this token, or null for a locally minted one. */
    UUID entraObjectIdOf(Authentication authentication);
}
