---
title: Identity Provider
---

# Identity Provider (OIDC)

## Built-in admin login (development / no-IdP mode)

When `ENTRA_ENABLED=false` (the default), the operator frontend shows a username/password form
instead of the "Login with Microsoft" button. The backend exposes `POST /api/v1/public/auth/login`
and issues a short-lived HS256 JWT signed with `JWT_DEV_SECRET`.

Configure via environment variables:

```dotenv
ENTRA_ENABLED=false
DEFAULT_ADMIN_EMAIL=admin@local
DEFAULT_ADMIN_PASSWORD=changeme-please
JWT_DEV_SECRET=change-me-for-staging
```

On startup the backend creates the administrator from the configured email and a BCrypt hash of the password **only when no
user holds the `REGISTRY_ADMIN` role**. An existing account is never modified: changing `DEFAULT_ADMIN_PASSWORD` later does
not reset the password, re-enable a disabled account or re-add the role. The seeded account is flagged
`must_change_password`; set your own password through an administrator-issued password reset. A production start fails
when the seeded account still has the flag, or still accepts `DEFAULT_ADMIN_PASSWORD`, 24 hours after it was created.

!!! warning "Not for production"
    The HS256 dev secret and the built-in admin are intended for local development and demo
    environments only. For production, configure a real identity provider below and set
    `ENTRA_ENABLED=true` + `JWT_ISSUER_URI=<your-issuer>`. The `/api/v1/public/auth/login`
    endpoint returns 404 when `ENTRA_ENABLED=true`.



The backend is an OAuth2 Resource Server. It accepts JWTs from any OIDC-compliant provider.

## Microsoft Entra ID (recommended)

1. Register an application in Azure Portal → App registrations
2. Add API permissions: `openid`, `profile`, `email`
3. Define App Roles: `REGISTRY_ADMIN`, `AUDIT`, `ISSUER`, `INVESTOR`, `COMPANY_ADMIN`
4. Set environment variables:
   ```dotenv
   JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_ISSUER=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_CLIENT_ID=<app-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Optionally, if running Kong Enterprise/Konnect, you can additionally terminate OIDC at the
gateway using `gateway/plugins/oidc-entra.yml` — the backend validates the JWT itself either way,
so this is defense-in-depth, not a requirement.

## Self-managed Keycloak

1. Create a realm and client
2. Add realm roles matching the role names above
3. Configure token mapper to include roles in JWT `roles` claim
4. Set environment variables:
   ```dotenv
   JWT_ISSUER_URI=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_ISSUER=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_CLIENT_ID=<client-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Optionally, terminate OIDC at Kong too using `gateway/plugins/oidc-self-managed.yml` (Enterprise/Konnect only).

## JWT claims expected

The backend resolves each validated JWT to an `app_user` row; it does not rely on gateway-injected
identity headers. Generic OIDC tokens need a stable `sub` claim and an email claim for first-time
provisioning. Entra tokens use `oid` as the stable identity and accept `preferred_username`,
`email`, or `upn` for the email address. New identities are auto-provisioned as disabled accounts
with no roles and must be approved by an operator before use; persisted roles and entity scope are
authoritative after approval. There is no Kong-side entity-mapping step in this repository's OSS Kong setup.
