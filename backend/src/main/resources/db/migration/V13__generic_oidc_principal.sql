-- Generic (non-Entra) OIDC principal resolution (Track 6-4).
--
-- The JWKS decoder (JwtDecoderFactory.issuerBacked) already validates a token from ANY OIDC
-- issuer configured via JWT_ISSUER_URI, not just Entra. But DefaultPrincipalResolver used to
-- unconditionally treat every non-locally-minted token as an Entra token, resolving/provisioning
-- on Entra-specific claims (`oid`, `tid`) and mislabeling every such account UserAuthProvider.ENTRA
-- regardless of which issuer actually signed the token. A bank on Okta/Keycloak/ForgeRock/Auth0
-- whose tokens don't carry an `oid` claim would either be silently mislabeled or, if no
-- email/upn/preferred_username claim was present either, rejected outright with a 403 despite the
-- token cryptographically validating fine.
--
-- external_subject stores the OIDC `sub` claim for a principal resolved this way — the one
-- identifier every OIDC provider guarantees is stable, unlike Entra's `oid`.
ALTER TABLE app_user ADD COLUMN external_subject VARCHAR(255);

COMMENT ON COLUMN app_user.external_subject IS
    'OIDC `sub` claim for a principal resolved via a non-Entra OIDC issuer (JWT_ISSUER_URI). '
    'NULL for LOCAL and ENTRA accounts, which are keyed by id / entra_object_id respectively.';

CREATE UNIQUE INDEX ux_app_user_external_subject
    ON app_user (external_subject)
    WHERE external_subject IS NOT NULL;

ALTER TABLE app_user DROP CONSTRAINT chk_app_user_auth_provider;
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_auth_provider CHECK (auth_provider IN ('LOCAL','ENTRA','OIDC'));
