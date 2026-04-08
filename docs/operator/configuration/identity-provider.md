---
id: identity-provider
title: Identity Provider
sidebar_position: 4
---

# Identity Provider (OIDC)

The backend is an OAuth2 Resource Server. It accepts JWTs from any OIDC-compliant provider.

## Microsoft Entra ID (recommended)

1. Register an application in Azure Portal → App registrations
2. Add API permissions: `openid`, `profile`, `email`
3. Define App Roles: `REGISTRY_ADMIN`, `AUDIT`, `ISSUER`, `INVESTOR`, `COMPANY_ADMIN`
4. Set environment variables:
   ```dotenv
   JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
   JWT_AUDIENCE=api://<app-id>
   ```

Configure Kong OIDC plugin using `gateway/plugins/oidc-entra.yml`.

## Self-managed Keycloak

1. Create a realm and client
2. Add realm roles matching the role names above
3. Configure token mapper to include roles in JWT `roles` claim
4. Set environment variables:
   ```dotenv
   JWT_ISSUER_URI=https://keycloak.yourhost.com/realms/ewpg
   JWT_AUDIENCE=registerwerk
   ```

Configure Kong OIDC plugin using `gateway/plugins/oidc-self-managed.yml`.

## JWT claims expected

The backend's `JwtEntityClaimsConverter` reads:
- `sub` — user subject (mapped to entity via Kong entity-mapper)
- `roles` — list of role strings (e.g. `["ISSUER", "COMPANY_ADMIN"]`)

The Kong plugin injects:
- `X-Entity-Id` — UUID of the legal entity
- `X-Entity-Roles` — comma-separated roles
