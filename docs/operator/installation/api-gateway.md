---
id: api-gateway
title: API Gateway (Kong)
sidebar_position: 5
---

# API Gateway (Kong)

Kong 3.8 acts as the single entry point for all API traffic. It handles authentication, rate limiting, caching, and entity header injection.

## Starting Kong

```bash
# Start Kong + its database
docker compose up -d kong-db
docker compose run --rm kong-migrations

# Start Kong and Konga
docker compose up -d kong konga
```

## Declarative configuration

Kong is configured via `gateway/kong.yml` in deck format. To apply changes:

```bash
deck sync --config gateway/kong.yml
```

## Key plugins

| Plugin | Purpose |
|---|---|
| `oidc` | Validates JWTs from Entra ID or Keycloak |
| `proxy-cache` | Caches GET 200 responses for 30 seconds |
| `rate-limiting` | 300 requests/minute per consumer |
| `cors` | Cross-origin headers for Angular frontends |
| `request-size-limiting` | 20 MB max request body |

## Entity header injection

The custom Lua plugin `entity-mapper` reads the `sub` claim from the JWT, looks up the entity mapping in Redis, and injects:
- `X-Entity-Id` — the legal entity UUID
- `X-Entity-Roles` — comma-separated role list

The backend trusts these headers **only** from Kong's internal network.

## Konga admin UI

```
http://localhost:1337
```

Default credentials on first run: create an admin account on first visit.
