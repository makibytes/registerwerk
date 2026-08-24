package de.makibytes.registerwerk.auth.api;

public enum UserAuthProvider {
    LOCAL,
    ENTRA,
    /** Any non-Entra OIDC issuer (Okta, Keycloak, ForgeRock, Auth0, …), validated generically
     *  against {@code JWT_ISSUER_URI}'s JWKS. Entra-specific features (2FA status, step-up auth
     *  context via Graph, the operator support console) do not apply — see
     *  {@code de.makibytes.registerwerk.entra} module. */
    OIDC
}
