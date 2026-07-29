---
id: identity-provider
title: Identity Provider
sidebar_position: 4
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

On startup the backend seeds (or refreshes) a row in the `app_user` table with the configured
email and a BCrypt hash of the password. Rotating the password is as simple as changing
`DEFAULT_ADMIN_PASSWORD` and restarting the service — the hash is updated on every boot.

:::warning Not for production
The HS256 dev secret and the built-in admin are intended for local development and demo
environments only. For production, configure a real identity provider below and set
`ENTRA_ENABLED=true` + `JWT_ISSUER_URI=<your-issuer>`. The `/api/v1/public/auth/login`
endpoint returns 404 when `ENTRA_ENABLED=true`.
:::


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

The backend's `JwtEntityClaimsConverter` reads claims directly off the validated JWT — it does
not rely on any gateway-injected header:
- `sub` — user subject
- `roles` — list of role strings (e.g. `["ISSUER", "COMPANY_ADMIN"]`), turned into `ROLE_*` authorities
- `entity_id` — the legal entity UUID, for multi-tenant scoping

Configure your IdP's token/claims mapping so these are present in the issued JWT. There is no
Kong-side entity-mapping step in this repo's OSS Kong setup.
