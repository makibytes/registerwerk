package de.makibytes.registerwerk.entra.api;

import de.makibytes.registerwerk.auth.api.AppUser;

/**
 * Decides where a user's identity is hosted, and therefore what Registerwerk may do with it.
 *
 * <p>Separate from {@link EntraDirectoryPort} because callers need the answer <em>before</em>
 * reaching for Graph: attempting to manage a federated user's methods would produce a 404 for a
 * principal that genuinely does not exist in our tenant, which reads as a bug rather than the
 * category error it is.
 */
public interface EntraIdentityGate {

    /**
     * Where this account's identity lives.
     *
     * <p>Ground truth is the token's {@code tid}, captured on the account row at sign-in. The
     * legal entity's configured model is only consulted before the user has ever signed in, when
     * it records operator intent rather than observed fact.
     */
    EntraIdentityModel classify(AppUser user);

    /**
     * Whether a Temporary Access Pass can be issued for this account.
     *
     * <p>False for an <em>external</em> B2B guest: Entra does not permit a TAP for a guest whose
     * credentials live in another tenant. The support console disables the action and points the
     * operator at method-reset plus re-registration instead.
     */
    boolean supportsTemporaryAccessPass(AppUser user);
}
